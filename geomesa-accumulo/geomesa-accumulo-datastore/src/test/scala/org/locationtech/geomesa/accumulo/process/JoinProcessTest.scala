/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.process

import org.geotools.filter.text.ecql.ECQL
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.TestWithMultipleSfts
import org.locationtech.geomesa.accumulo.util.SelfClosingIterator
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class JoinProcessTest extends Specification with TestWithMultipleSfts {

  sequential

  val sft1 = createNewSchema("track:String,age:Int,weight:Int,dtg:Date,*geom:LineString:srid=4326")
  val sft2 = createNewSchema("track:String:index=true,dtg:Date,*geom:Point:srid=4326")

  val features1 = (0 until 10).map { i =>
    val sf = new ScalaSimpleFeature(i.toString, sft1)
    sf.setAttribute(0, i.asInstanceOf[AnyRef])
    sf.setAttribute(1, i.asInstanceOf[AnyRef])
    sf.setAttribute(2, i.asInstanceOf[AnyRef])
    sf.setAttribute(3, "2015-01-01T00:00:00.000Z")
    sf.setAttribute(4, "LINESTRING(0 0, 1 1)")
    sf
  }

  val features2 = (0 until 100).map { i =>
    val sf = new ScalaSimpleFeature(i.toString, sft2)
    sf.setAttribute(0, (i / 10).asInstanceOf[AnyRef])
    sf.setAttribute(1, "2015-01-01T00:00:00.000Z")
    sf.setAttribute(2, "POINT(0 0)")
    sf
  }

  addFeatures(sft1, features1)
  addFeatures(sft2, features2)

  "JoinProcess" should {
    "join between schemas" in {
      val fc1 = ds.getFeatureSource(sft1.getTypeName).getFeatures(ECQL.toFilter("age = 5"))
      val fc2 = ds.getFeatureSource(sft2.getTypeName).getFeatures()

      val process = new JoinProcess
      val results = process.execute(fc1, fc2, "track", null, null, false, null, null, null, null)

      val features = SelfClosingIterator(results).toList
      features must haveLength(10)
      forall(features)(_.getAttribute("track") mustEqual "5")
    }
  }
}
