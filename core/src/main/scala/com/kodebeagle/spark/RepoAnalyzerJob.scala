/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kodebeagle.spark

import java.io.{File, PrintWriter}
import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}

import com.kodebeagle.configuration.KodeBeagleConfig
import com.kodebeagle.indexer.{TypeReference, Comments, SourceFile}
import com.kodebeagle.logging.Logger
import com.kodebeagle.model.GithubRepo.GithubRepoInfo
import com.kodebeagle.model.{GithubRepo, JavaRepo}
import com.kodebeagle.util.SparkIndexJobHelper._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.apache.spark.{TaskContext, SerializableWritable, SparkConf}

import scala.util.Try

object RepoAnalyzerJob extends Logger {

  private val language = "Java"
  val totalExecutorSize: AtomicLong = new AtomicLong(0)

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
      .setMaster(KodeBeagleConfig.sparkMaster)
      .setAppName("RepoAnalyzerJob")
    val sc = createSparkContext(conf)
    sc.hadoopConfiguration.set("dfs.replication", "1")
    val sqlContext = new SQLContext(sc)

    val metaDataLoc = Try {
      val arg = args(0)
      if (arg.contains(",")) {
        arg.split(",").map(s => s"${KodeBeagleConfig.repoMetaDataHdfsPath}/${s.trim}")
      } else if (arg.contains("-")) {
        // This is a range
        val rangeArr = arg.split("-").map(_.trim.toInt)
        val start = rangeArr.head
        val end = rangeArr.last
        val chunkSize = KodeBeagleConfig.chunkSize.toInt
        val completeRange = (start until end).grouped(chunkSize).toList
        completeRange.map(r => {
          val head = r.head
          s"${head}-${head + KodeBeagleConfig.chunkSize.toInt - 1}"
        }).map(s => s"${KodeBeagleConfig.repoMetaDataHdfsPath}/${s.trim}").toArray
      } else {
        Array(arg)
      }
    }.toOption.getOrElse(Array(KodeBeagleConfig.repoMetaDataHdfsPath))

    val df = sqlContext.read.json(metaDataLoc: _*)
    val repoInfos = selectFromDf(df)

    val confBroadcast = sc.broadcast(new SerializableWritable(sc.hadoopConfiguration))
    // filter: size < 1 GB, and stars > 5 and language in ('Scala', 'Java')
    val javaReposRDD = repoInfos.filter(filterRepo)
      .flatMap(rInfo => GithubRepo(confBroadcast.value.value, rInfo))
      .map(repo => new JavaRepo(repo))
    handleJavaIndices(confBroadcast, javaReposRDD)
  }

  private def selectFromDf(df: DataFrame) = {
    val repoInfos = df.select("id", "name", "full_name", "owner.login",
      "private", "fork", "size", "stargazers_count", "watchers_count",
      "forks_count", "subscribers_count", "default_branch", "language")
      .rdd.map { r =>
      val id: Long = Option(r.get(0)).getOrElse(0L).asInstanceOf[Long]
      val name: String = Option(r.get(1)).getOrElse("").asInstanceOf[String]
      val fullName: String = Option(r.get(2)).getOrElse("").asInstanceOf[String]
      val owner_Login: String = Option(r.get(3)).getOrElse("").asInstanceOf[String]
      val isPrivate: Boolean = Option(r.get(4)).getOrElse(false).asInstanceOf[Boolean]
      val isFork: Boolean = Option(r.get(5)).get.asInstanceOf[Boolean]
      val size: Long = Option(r.get(6)).get.asInstanceOf[Long]
      val stars: Long = Option(r.get(7)).getOrElse(0L).asInstanceOf[Long]
      val watchers: Long = Option(r.get(8)).getOrElse(0L).asInstanceOf[Long]
      val forks: Long = Option(r.get(9)).getOrElse(0L).asInstanceOf[Long]
      val subscribers: Long = Option(r.get(10)).getOrElse(0L).asInstanceOf[Long]
      val defaultBranch: String = Option(r.get(11)).get.asInstanceOf[String]
      val language: String = Option(r.get(12)).getOrElse("").asInstanceOf[String]

      GithubRepoInfo(
        id, owner_Login, name, fullName, isPrivate, isFork, size, watchers,
        language, forks, subscribers, defaultBranch, stars)
    }
    repoInfos
  }

  // **** Helper methods for the job ******// 
  private def filterRepo(ri: GithubRepoInfo): Boolean = {
    ri.size < 1000 * 1000 &&
      ri.stargazersCount > KodeBeagleConfig.minStars && Option(ri.language).isDefined &&
      Seq("Java", "Scala").exists(_.equalsIgnoreCase(ri.language) && !0L.equals(ri.id)
        && !ri.name.isEmpty && !ri.fullName.isEmpty && !ri.login.isEmpty)
  }

  private def handleJavaIndices(confBroadcast: Broadcast[SerializableWritable[Configuration]],
                                javafileIndicesRDD: RDD[JavaRepo]) = {

    javafileIndicesRDD.foreachPartition((repos: Iterator[JavaRepo]) => {
      val fs = FileSystem.get(confBroadcast.value.value)
      repos.foreach(handleJavaRepos(fs))
    })
  }

  private def checksize(javarepo: JavaRepo): Boolean = {
    val currTotal = totalExecutorSize.get()
    val total =  currTotal + javarepo.baseRepo.files.size
    (total > 25000) && (currTotal > 1000)
  }

  private def handleJavaRepos(fs: FileSystem)(javarepo: JavaRepo) = {
    import com.kodebeagle.logging.LoggerUtils._
    val login = javarepo.baseRepo.repoInfo.get.login
    val repoName = javarepo.baseRepo.repoInfo.get.name

    while (checksize(javarepo)) {
      log.sparkInfo(s"Size is ${totalExecutorSize.get}, going to sleep for [$login/$repoName].")
      Thread.sleep(10000)
    }
    totalExecutorSize.getAndAdd(javarepo.baseRepo.files.size)
    log.sparkInfo(s"Processing repo ${login}/${repoName}, size is ${totalExecutorSize.get}")

    javarepo.files.size < 20000 match {
      case true => handleJavaRepo(fs, javarepo, login, repoName)
      case false => log.sparkInfo(s"Repo ${login}/${repoName} has > 20K files, ignoring for now")
    }

    totalExecutorSize.getAndAdd(-javarepo.baseRepo.files.size)
    log.sparkInfo(s"Done processing repo ${login}/${repoName}")
  }

  private def handleJavaRepo(fs: FileSystem, javarepo: JavaRepo,
                             login: String, repoName: String): String = {
    import scala.sys.process._
    val srchRefFileName = s"/tmp/kodebeagle-srch-$login~$repoName"
    val srcFileName = s"/tmp/kodebeagle-src-$login~$repoName"
    val metaFileName = s"/tmp/kodebeagle-meta-$login~$repoName"
    val typesInfoFileName = s"/tmp/kodebeagle-typesInfo-$login~$repoName"
    val commentsFileName = s"/tmp/kodebeagle-javadoc-$login~$repoName"

    val srchrefWriter = new PrintWriter(new File(srchRefFileName))
    val srcWriter = new PrintWriter(new File(srcFileName))
    val metaWriter = new PrintWriter(new File(metaFileName))
    val typesInfoWriter = new PrintWriter(new File(typesInfoFileName))
    val commentsWriter = new PrintWriter(new File(commentsFileName))

    try {
      if (javarepo.files.isEmpty) {
        log.info(s"Repo $login/$repoName does not seem to contain anything java.")
      }
      javarepo.files.foreach(file => {
        val fileLoc = Option(file.repoFileLocation)
        writeIndex("java", "typereference", file.searchableRefs, fileLoc, srchrefWriter)
        writeIndex("java", "filemetadata", file.fileMetaData, fileLoc, metaWriter)
        writeIndex("java", "sourcefile", SourceFile(file.repoId, file.repoFileLocation,
          file.fileContent), fileLoc, srcWriter)
        writeIndex("java", "documentation", Comments(file.javaDocs), fileLoc, commentsWriter)
        val typesInfoEntry = toJson(file.typesInFile)
        typesInfoWriter.write(typesInfoEntry + "\n")
        file.free()
      })
    } finally {
      Seq(srchrefWriter, srcWriter, metaWriter, typesInfoWriter, commentsWriter).foreach(_.close())
    }

    val moveIndex: (String, String) => Unit = moveFromLocal(login, repoName, fs)
    moveIndex(srcFileName, "sources")
    moveIndex(srchRefFileName, "tokens")
    moveIndex(metaFileName, "meta")
    moveIndex(typesInfoFileName, "typesinfo")
    moveIndex(commentsFileName, "comments")

    val repoCleanCmd = s"rm -rf /tmp/kodebeagle/$login/$repoName"
    log.info(s"Executing command: $repoCleanCmd")
    repoCleanCmd.!!
    s"rm -f $srcFileName $srchRefFileName $metaFileName $typesInfoFileName $commentsFileName".!!
  }

  private def writeIndex[T <: AnyRef <% Product with Serializable](index: String, typeName: String,
                                                                   indices: T, id: Option[String],
                                                                   writer: PrintWriter) = {
    writer.write(toIndexTypeJson(index, typeName, indices, id) + "\n")
  }

  private def moveFromLocal(login: String, repoName: String, fs: FileSystem)
                           (indxFileName: String, indxName: String) = {
    fs.moveFromLocalFile(new Path(indxFileName),
      new Path(s"${KodeBeagleConfig.repoIndicesHdfsPath}$language/$indxName/$login~$repoName"))
  }
}
