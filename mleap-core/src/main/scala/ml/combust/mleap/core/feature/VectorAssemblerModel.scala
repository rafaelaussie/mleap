package ml.combust.mleap.core.feature

import ml.combust.mleap.core.Model
import ml.combust.mleap.core.annotation.SparkCode
import ml.combust.mleap.core.types._
import ml.combust.mleap.tensor.{DenseTensor, SparseTensor}
import org.apache.spark.ml.linalg.{Vector, Vectors}

import scala.collection.mutable

/** Class for a vector assembler model.
  *
  * Vector assemblers take an input set of doubles and vectors
  * and create a new vector out of them. This is primarily used
  * to get all desired features into one vector before training
  * a model.
  */
@SparkCode(uri = "https://github.com/apache/spark/blob/v2.0.0/mllib/src/main/scala/org/apache/spark/ml/feature/VectorAssembler.scala")
case class VectorAssemblerModel(inputShapes: Seq[DataShape]) extends Model {
  assert(inputShapes.find(s => !s.isScalar && !s.isTensor) == None, "must provide scalar and vector shapes as inputs")

  val outputSize: Int = inputShapes.map {
    case ScalarShape(_) => 1
    case TensorShape(Some(Seq(size)), _) => size
    case _ => throw new IllegalArgumentException("must provide scalar and vector shapes as inputs")
  }.sum

  /** Assemble a feature vector from a set of input features.
    *
    * @param vv all input feature values
    * @return assembled vector
    */
  def apply(vv: Seq[Any]): Vector = {
    val indices = mutable.ArrayBuilder.make[Int]
    val values = mutable.ArrayBuilder.make[Double]
    var cur = 0
    vv.foreach {
      case v: Double =>
        if (v != 0.0) {
          indices += cur
          values += v
        }
        cur += 1
      case tensor: DenseTensor[_] if tensor.dimensions.size == 1 =>
        val dTensor = tensor.asInstanceOf[DenseTensor[Double]]
        dTensor.values.indices.foreach {
          i =>
            val v = dTensor.values(i)
            if(v != 0.0) {
              indices += cur + i
              values += v
            }
        }
        cur += dTensor.values.length
      case tensor: SparseTensor[_] if tensor.dimensions.size == 1 =>
        val dTensor = tensor.asInstanceOf[SparseTensor[Double]]
        var idx = 0
        dTensor.indices.map(_.head).foreach {
          i =>
            val v = dTensor.values(idx)
            if(v != 0.0) {
              indices += cur + i
              values += v
            }
            idx += 1
        }
        cur += dTensor.dimensions.head
      case vec: Vector =>
        vec.foreachActive { case (i, v) =>
          if (v != 0.0) {
            indices += cur + i
            values += v
          }
        }
        cur += vec.size
      case v: java.math.BigDecimal =>
        val d = v.doubleValue()
        if (d != 0.0) {
          indices += cur
          values += d
        }
        cur += 1
      case Some(v: Double) =>
        if(v != 0.0) {
          indices += cur
          values += v
        }
        cur += 1
    }
    Vectors.sparse(cur, indices.result(), values.result()).compressed
  }

  override def inputSchema: StructType = {
    val inputFields = inputShapes.zipWithIndex.map {
      case (shape, i) => StructField(s"input$i", DataType(BasicType.Double, shape))
    }

    StructType(inputFields).get
  }

  override def outputSchema: StructType = StructType("output" -> TensorType.Double(outputSize)).get
}
