package io.joern.javasrc2cpg.passes

import better.files.File
import com.github.javaparser.{JavaParser, ParserConfiguration}
import com.github.javaparser.ParserConfiguration.LanguageLevel
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node.Parsedness
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.{
  ClassLoaderTypeSolver,
  JarTypeSolver,
  ReflectionTypeSolver
}
import io.joern.javasrc2cpg.JavaSrc2Cpg.JavaSrcEnvVar
import io.joern.javasrc2cpg.astcreation.AstCreator
import io.joern.javasrc2cpg.passes.AstCreationPass.*
import io.joern.javasrc2cpg.typesolvers.{EagerSourceTypeSolver, JdkJarTypeSolver, SimpleCombinedTypeSolver}
import io.joern.javasrc2cpg.util.Delombok.DelombokMode
import io.joern.javasrc2cpg.util.Delombok.DelombokMode.*
import io.joern.javasrc2cpg.util.{Delombok, SourceParser}
import io.joern.javasrc2cpg.{Config, JavaSrc2Cpg}
import io.joern.x2cpg.SourceFiles
import io.joern.x2cpg.datastructures.Global
import io.joern.x2cpg.passes.frontend.XTypeRecoveryConfig
import io.joern.x2cpg.utils.dependency.DependencyResolver
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.passes.ConcurrentWriterCpgPass
import org.slf4j.LoggerFactory

import java.net.URLClassLoader
import java.nio.file.{Path, Paths}
import scala.collection.parallel.CollectionConverters.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional
import scala.util.{Success, Try}

class AstCreationPass(config: Config, cpg: Cpg, sourcesOverride: Option[List[String]] = None)
    extends ConcurrentWriterCpgPass[String](cpg) {

  val global: Global = new Global()
  private val logger = LoggerFactory.getLogger(classOf[AstCreationPass])

  val (sourceParser, symbolSolver) = initParserAndUtils(config)

  override def generateParts(): Array[String] = sourceParser.relativeFilenames.toArray

  override def runOnPart(diffGraph: DiffGraphBuilder, filename: String): Unit = {
    sourceParser.parseAnalysisFile(filename, !config.disableFileContent) match {
      case Some(compilationUnit, fileContent) =>
        symbolSolver.inject(compilationUnit)
        val contentToUse = if (!config.disableFileContent) fileContent else None
        diffGraph.absorb(
          new AstCreator(filename, compilationUnit, contentToUse, global, symbolSolver)(config.schemaValidation)
            .createAst()
        )

      case None => logger.warn(s"Skipping AST creation for $filename")
    }
  }

  private def initParserAndUtils(config: Config): (SourceParser, JavaSymbolSolver) = {
    val dependencies = getDependencyList(config.inputPath)
    val sourceParser = SourceParser(config, sourcesOverride)
    val symbolSolver = createSymbolSolver(config, dependencies, sourceParser)
    (sourceParser, symbolSolver)
  }

  private def getDependencyList(inputPath: String): List[String] = {
    if (config.fetchDependencies) {
      DependencyResolver.getDependencies(Paths.get(inputPath)) match {
        case Some(deps) => deps.toList
        case None =>
          logger.warn(s"Could not fetch dependencies for project at path $inputPath")
          List()
      }
    } else {
      logger.info("dependency resolving disabled")
      List()
    }
  }

  private def createSymbolSolver(
    config: Config,
    dependencies: List[String],
    sourceParser: SourceParser
  ): JavaSymbolSolver = {
    val combinedTypeSolver = new SimpleCombinedTypeSolver()
    val symbolSolver       = new JavaSymbolSolver(combinedTypeSolver)

    val jdkPathFromEnvVar = Option(System.getenv(JavaSrcEnvVar.JdkPath.name))
    val jdkPath = (config.jdkPath, jdkPathFromEnvVar) match {
      case (None, None) =>
        val javaHome = System.getProperty("java.home")
        logger.info(s"No explicit jdk-path set in , so using system java.home for JDK type information: $javaHome")
        javaHome

      case (None, Some(jdkPath)) =>
        logger.info(
          s"Using JDK path from environment variable ${JavaSrcEnvVar.JdkPath.name} for JDK type information: $jdkPath"
        )
        jdkPath

      case (Some(jdkPath), _) =>
        logger.info(s"Using JDK path set with jdk-path option for JDK type information: $jdkPath")
        jdkPath
    }

    combinedTypeSolver.addNonCachingTypeSolver(
      JdkJarTypeSolver.fromJdkPath(jdkPath, useCache = config.cacheJdkTypeSolver)
    )

    val sourceTypeSolver =
      EagerSourceTypeSolver(sourceParser, combinedTypeSolver, symbolSolver)

    combinedTypeSolver.addCachingTypeSolver(sourceTypeSolver)

    // Add solvers for inference jars
    val jarsList = config.inferenceJarPaths.flatMap(recursiveJarsFromPath).toList
    if (config.inferenceJarPaths.isEmpty) {
      logger.debug("No inference jar paths given")
    } else if (jarsList.isEmpty) {
      logger.warn(s"Could not find any inference jars at provided paths: ${config.inferenceJarPaths.mkString(",")}")
    } else {
      logger.debug(s"Using inference jars: ${jarsList.mkString(":")}")
    }
    (jarsList ++ dependencies)
      .flatMap { path =>
        Try(new JarTypeSolver(path)).toOption
      }
      .foreach { combinedTypeSolver.addNonCachingTypeSolver(_) }

    symbolSolver
  }

  private def recursiveJarsFromPath(path: String): List[String] = {
    Try(File(path)) match {
      case Success(file) if file.isDirectory =>
        file.listRecursively
          .map(_.canonicalPath)
          .filter(_.endsWith(".jar"))
          .toList

      case Success(file) if file.canonicalPath.endsWith(".jar") =>
        List(file.canonicalPath)

      case _ =>
        Nil
    }
  }
}
