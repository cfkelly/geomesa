/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.index

import com.typesafe.scalalogging.LazyLogging
import org.apache.accumulo.core.data.{Range => aRange}
import org.apache.hadoop.io.Text
import org.geotools.factory.Hints
import org.locationtech.geomesa.accumulo.data.stats.GeoMesaStats
import org.locationtech.geomesa.accumulo.data.tables.RecordTable
import org.locationtech.geomesa.accumulo.index.QueryHints.RichHints
import org.locationtech.geomesa.accumulo.index.Strategy._
import org.locationtech.geomesa.accumulo.iterators.{BinAggregatingIterator, KryoLazyDensityIterator, KryoLazyFilterTransformIterator, KryoLazyStatsIterator, KryoVisibilityRowEncoder}
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.locationtech.geomesa.utils.index.VisibilityLevel
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.{And, Filter, Id, Or}

import scala.collection.JavaConversions._


object RecordIdxStrategy extends StrategyProvider {

  // top-priority index - always 1 if there are actually ID filters
  override protected def statsBasedCost(sft: SimpleFeatureType,
                                        filter: QueryFilter,
                                        transform: Option[SimpleFeatureType],
                                        stats: GeoMesaStats): Option[Long] = {
    if (filter.primary.isDefined) Some(1L) else Some(Long.MaxValue)
  }

  // top-priority index - always 1
  override protected def indexBasedCost(sft: SimpleFeatureType,
                                        filter: QueryFilter,
                                        transform: Option[SimpleFeatureType]): Long =
  if (filter.primary.isDefined) 1 else Long.MaxValue

  def intersectIdFilters(filter: Filter): Set[String] = {
    filter match {
      case f: And => f.getChildren.map(intersectIdFilters).reduceLeftOption(_ intersect _).getOrElse(Set.empty)
      case f: Or  => f.getChildren.flatMap(intersectIdFilters).toSet
      case f: Id  => f.getIDs.map(_.toString).toSet
      case _ => throw new IllegalArgumentException(s"Expected ID filter, got ${filterToString(filter)}")
    }
  }
}

class RecordIdxStrategy(val filter: QueryFilter) extends Strategy with LazyLogging {

  override def getQueryPlan(queryPlanner: QueryPlanner, hints: Hints, output: ExplainerOutputType) = {
    val ds = queryPlanner.ds
    val sft = queryPlanner.sft
    val featureEncoding = queryPlanner.ds.getFeatureEncoding(sft)
    val prefix = sft.getTableSharingPrefix

    val ranges = filter.primary match {
      case None =>
        // allow for full table scans
        filter.secondary.foreach { f =>
          logger.warn(s"Running full table scan for schema ${sft.getTypeName} with filter ${filterToString(f)}")
        }
        val start = new Text(prefix)
        Seq(new aRange(start, true, aRange.followingPrefix(start), false))

      case Some(primary) =>
        // Multiple sets of IDs in a ID Filter are ORs. ANDs of these call for the intersection to be taken.
        // intersect together all groups of ID Filters, producing a set of IDs
        val identifiers = RecordIdxStrategy.intersectIdFilters(primary)
        output(s"Extracted ID filter: ${identifiers.mkString(", ")}")
        identifiers.toSeq.map(id => aRange.exact(RecordTable.getRowKey(prefix, id)))
    }

    if (ranges.isEmpty) { EmptyPlan(filter) } else {
      val table = ds.getTableName(sft.getTypeName, RecordTable)
      val threads = ds.getSuggestedThreads(sft.getTypeName, RecordTable)
      val dupes = false // record table never has duplicate entries

      if (sft.getSchemaVersion > 5) {
        // optimized path when we know we're using kryo serialization
        val perAttributeIter = sft.getVisibilityLevel match {
          case VisibilityLevel.Feature   => Seq.empty
          case VisibilityLevel.Attribute => Seq(KryoVisibilityRowEncoder.configure(sft))
        }
        val (iters, kvsToFeatures) = if (hints.isBinQuery) {
          // use the server side aggregation
          val iter = BinAggregatingIterator.configureDynamic(sft, RecordTable, filter.secondary, hints, dupes)
          (Seq(iter), BinAggregatingIterator.kvsToFeatures())
        } else if (hints.isDensityQuery) {
          val iter = KryoLazyDensityIterator.configure(sft, RecordTable, filter.secondary, hints)
          (Seq(iter), KryoLazyDensityIterator.kvsToFeatures())
        } else if (hints.isStatsIteratorQuery) {
          val iter = KryoLazyStatsIterator.configure(sft, RecordTable, filter.secondary, hints, dupes)
          (Seq(iter), KryoLazyStatsIterator.kvsToFeatures(sft))
        } else {
          val iter = KryoLazyFilterTransformIterator.configure(sft, filter.secondary, hints)
          (iter.toSeq, queryPlanner.kvsToFeatures(sft, hints.getReturnSft, RecordTable))
        }
        BatchScanPlan(filter, table, ranges, iters ++ perAttributeIter, Seq.empty, kvsToFeatures, threads, dupes)
      } else {
        val iters = if (filter.secondary.isDefined || hints.getTransformSchema.isDefined) {
          Seq(configureRecordTableIterator(sft, featureEncoding, filter.secondary, hints))
        } else {
          Seq.empty
        }
        val kvsToFeatures = if (hints.isBinQuery) {
          BinAggregatingIterator.nonAggregatedKvsToFeatures(sft, RecordTable, hints, featureEncoding)
        } else {
          queryPlanner.kvsToFeatures(sft, hints.getReturnSft, RecordTable)
        }
        BatchScanPlan(filter, table, ranges, iters, Seq.empty, kvsToFeatures, threads, dupes)
      }
    }
  }
}
