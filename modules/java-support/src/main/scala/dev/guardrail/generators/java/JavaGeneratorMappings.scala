package dev.guardrail.generators.java

import com.github.javaparser.StaticJavaParser
import _root_.scala.util.{ Failure, Success, Try }

import dev.guardrail.core.CoreTermInterp
import dev.guardrail.generators.spi.{ FrameworkLoader, ModuleMapperLoader }
import dev.guardrail.{ MissingDependency, UnparseableArgument }

object JavaGeneratorMappings {
  implicit def javaInterpreter = new CoreTermInterp[JavaLanguage](
    "dropwizard",
    xs => FrameworkLoader.load[JavaLanguage](xs, MissingDependency(xs.mkString(", "))),
    frameworkName => ModuleMapperLoader.load[JavaLanguage](frameworkName, MissingDependency(frameworkName)),
    { str =>
      Try(StaticJavaParser.parseImport(s"import ${str};")) match {
        case Success(value) => Right(value)
        case Failure(t)     => Left(UnparseableArgument("import", t.getMessage))
      }
    }
  )
}
