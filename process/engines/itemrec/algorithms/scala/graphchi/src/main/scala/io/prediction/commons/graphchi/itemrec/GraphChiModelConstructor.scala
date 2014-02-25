package io.prediction.commons.graphchi.itemrec

import grizzled.slf4j.Logger
import breeze.linalg._
import com.twitter.scalding.Args
import scala.io.Source

import io.prediction.commons.graphchi.itemrec.MatrixMarketReader
import io.prediction.commons.Config
import io.prediction.commons.modeldata.{ ItemRecScore }

object GraphChiModelConstructor {

  /* global */
  val logger = Logger(GraphChiDataPreparator.getClass)
  println(logger.isInfoEnabled)
  val commonsConfig = new Config

  // argument of this job
  case class JobArg(
    val inputDir: String,
    val appid: Int,
    val algoid: Int,
    val evalid: Option[Int],
    val modelSet: Boolean,
    val unseenOnly: Boolean,
    val numRecommendations: Int)

  def main(cmdArgs: Array[String]) {
    println("Running model constructor for GraphChi ...")
    println(cmdArgs.mkString(","))

    /* get arg */
    val args = Args(cmdArgs)

    val arg = JobArg(
      inputDir = args("inputDir"),
      appid = args("appid").toInt,
      algoid = args("algoid").toInt,
      evalid = args.optional("evalid") map (x => x.toInt),
      modelSet = args("modelSet").toBoolean,
      unseenOnly = args("unseenOnly").toBoolean,
      numRecommendations = args("numRecommendations").toInt
    )

    /* run job */
    modelCon(arg)
    cleanUp(arg)
  }

  def modelCon(arg: JobArg) = {

    // NOTE: if OFFLINE_EVAL, write to training modeldata and use evalid as appid
    val OFFLINE_EVAL = (arg.evalid != None)

    val modeldataDb = if (!OFFLINE_EVAL)
      commonsConfig.getModeldataItemRecScores
    else
      commonsConfig.getModeldataTrainingItemRecScores

    val appid = if (OFFLINE_EVAL) arg.evalid.get else arg.appid

    // user index file
    // uindex -> uid
    val usersMap: Map[Int, String] = Source.fromFile(s"${arg.inputDir}usersIndex.tsv").getLines()
      .map[(Int, String)] { line =>
        val (uindex, uid) = try {
          val data = line.split("\t")
          (data(0).toInt, data(1))
        } catch {
          case e: Exception => {
            throw new RuntimeException(s"Cannot get user index and uid in line: ${line}. ${e}")
          }
        }
        (uindex, uid)
      }.toMap

    case class ItemData(
      val iid: String,
      val itypes: Seq[String])

    // item index file (iindex iid itypes)
    // iindex -> ItemData
    val itemsMap: Map[Int, ItemData] = Source.fromFile(s"${arg.inputDir}itemsIndex.tsv")
      .getLines()
      .map[(Int, ItemData)] { line =>
        val (iindex, item) = try {
          val fields = line.split("\t")
          val itemData = ItemData(
            iid = fields(1),
            itypes = fields(2).split(",").toList
          )
          (fields(0).toInt, itemData)
        } catch {
          case e: Exception => {
            throw new RuntimeException(s"Cannot get item info in line: ${line}. ${e}")
          }
        }
        (iindex, item)
      }.toMap

    // ratings file (for unseen filtering) 
    val seenMap: Map[(Int, Int), Boolean] = if (arg.unseenOnly) {
      Source.fromFile(s"${arg.inputDir}ratings.mm")
        .getLines()
        // discard all empty line and comments
        .filter(line => (line.length != 0) && (!line.startsWith("%")))
        .drop(1) // 1st line is matrix size
        .map { line =>
          val (u, i) = try {
            val fields = line.split("""\s+""")
            // u, i, rating
            (fields(0).toInt, fields(1).toInt)
          } catch {
            case e: Exception => throw new RuntimeException(s"Cannot get user and item index from this line: ${line}. ${e}")
          }
          ((u, i) -> true)
        }.toMap
    } else {
      Map() // empty map
    }

    // feature x user matrix
    val userMatrix = MatrixMarketReader.readDense(s"${arg.inputDir}u.mm").t

    // feature x item matrix
    val itemMatrix = MatrixMarketReader.readDense(s"${arg.inputDir}i.mm").t

    // note: start form 0
    for (uindex <- 1 to userMatrix.cols if usersMap.contains(uindex)) {
      val scores = for (
        iindex <- 1 to itemMatrix.cols if (unseenItemFilter(arg.unseenOnly, uindex, iindex, seenMap)
          && validItemFilter(true, iindex, itemsMap))
      ) yield {
        // NOTE: DenseMatrix index starts from 0, so minus 1
        val score = userMatrix(::, uindex - 1) dot itemMatrix(::, iindex - 1)
        (iindex, score)
      }

      println(s"${uindex}: ${scores}")

      val topScores = scores
        .sortBy(_._2)(Ordering[Double].reverse)
        .take(arg.numRecommendations)

      println(s"$topScores")

      modeldataDb.insert(ItemRecScore(
        uid = usersMap(uindex),
        iids = topScores.map(x => itemsMap(x._1).iid),
        scores = topScores.map(_._2),
        itypes = topScores.map(x => itemsMap(x._1).itypes),
        appid = appid,
        algoid = arg.algoid,
        modelset = arg.modelSet))
    }

  }

  def unseenItemFilter(enable: Boolean, uindex: Int, iindex: Int, seenMap: Map[(Int, Int), Boolean]): Boolean = {
    if (enable) (!seenMap.contains((uindex, iindex))) else true
  }

  def validItemFilter(enable: Boolean, iindex: Int, validMap: Map[Int, Any]): Boolean = {
    if (enable) validMap.contains(iindex) else true
  }

  def cleanUp(arg: JobArg) = {

  }

}
