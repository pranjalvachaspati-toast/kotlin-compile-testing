package com.tschuchort.compiletesting

import io.github.classgraph.ClassGraph
import okio.Buffer
import org.jetbrains.kotlin.base.kapt3.KaptOptions
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.arguments.validateArguments
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.cli.jvm.plugins.ServiceLoaderLite
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

abstract class AbstractKotlinCompilation<A : CommonCompilerArguments> {
    /** Working directory for the compilation */
    var workingDir: File by default {
        val path = Files.createTempDirectory("Kotlin-Compilation")
        log("Created temporary working directory at ${path.toAbsolutePath()}")
        return@default path.toFile()
    }

    /**
     * Paths to directories or .jar files that contain classes
     * to be made available in the compilation (i.e. added to
     * the classpath)
     */
    var classpaths: List<File> = emptyList()

    /**
     * Paths to plugins to be made available in the compilation
     */
    var pluginClasspaths: List<File> = emptyList()

    /**
     * Compiler plugins that should be added to the compilation
     */
    var compilerPlugins: List<ComponentRegistrar> = emptyList()

    /**
     * Commandline processors for compiler plugins that should be added to the compilation
     */
    var commandLineProcessors: List<CommandLineProcessor> = emptyList()

    /** Source files to be compiled */
    var sources: List<SourceFile> = emptyList()

    /** Print verbose logging info */
    var verbose: Boolean = true

    /**
     * Helpful information (if [verbose] = true) and the compiler
     * system output will be written to this stream
     */
    var messageOutputStream: OutputStream = System.out

    /** Inherit classpath from calling process */
    var inheritClassPath: Boolean = false

    /** Suppress all warnings */
    var suppressWarnings: Boolean = false

    /** All warnings should be treated as errors */
    var allWarningsAsErrors: Boolean = false

    /** Report locations of files generated by the compiler */
    var reportOutputFiles: Boolean by default { verbose }

    /** Report on performance of the compilation */
    var reportPerformance: Boolean = false


    /** Additional string arguments to the Kotlin compiler */
    var kotlincArguments: List<String> = emptyList()

    /** Options to be passed to compiler plugins: -P plugin:<pluginId>:<optionName>=<value>*/
    var pluginOptions: List<PluginOption> = emptyList()

    /**
     * Path to the kotlin-stdlib-common.jar
     * If none is given, it will be searched for in the host
     * process' classpaths
     */
    var kotlinStdLibCommonJar: File? by default {
        findInHostClasspath(hostClasspaths, "kotlin-stdlib-common.jar",
            kotlinDependencyRegex("kotlin-stdlib-common"))
    }

    // Directory for input source files
    protected val sourcesDir get() = workingDir.resolve("sources")

    protected fun commonArguments(args: A, configuration: (args: A) -> Unit): A {
        args.pluginClasspaths = pluginClasspaths.map(File::getAbsolutePath).toTypedArray()

        args.verbose = verbose

        args.suppressWarnings = suppressWarnings
        args.allWarningsAsErrors = allWarningsAsErrors
        args.reportOutputFiles = reportOutputFiles
        args.reportPerf = reportPerformance

        configuration(args)

        /**
         * It's not possible to pass dynamic [CommandLineProcessor] instances directly to the [K2JSCompiler]
         * because the compiler discovers them on the classpath through a service locator, so we need to apply
         * the same trick as with [ComponentRegistrar]s: We put our own static [CommandLineProcessor] on the
         * classpath which in turn calls the user's dynamic [CommandLineProcessor] instances.
         */
        MainCommandLineProcessor.threadLocalParameters.set(
            MainCommandLineProcessor.ThreadLocalParameters(commandLineProcessors)
        )

        /**
         * Our [MainCommandLineProcessor] only has access to the CLI options that belong to its own plugin ID.
         * So in order to be able to access CLI options that are meant for other [CommandLineProcessor]s we
         * wrap these CLI options, send them to our own plugin ID and later unwrap them again to forward them
         * to the correct [CommandLineProcessor].
         */
        args.pluginOptions = pluginOptions.map { (pluginId, optionName, optionValue) ->
            "plugin:${MainCommandLineProcessor.pluginId}:${MainCommandLineProcessor.encodeForeignOptionName(pluginId, optionName)}=$optionValue"
        }.toTypedArray()

        /* Parse extra CLI arguments that are given as strings so users can specify arguments that are not yet
        implemented here as well-typed properties. */
        parseCommandLineArguments(kotlincArguments, args)

        validateArguments(args.errors)?.let {
            throw IllegalArgumentException("Errors parsing kotlinc CLI arguments:\n$it")
        }

        return args
    }

    /** Performs the compilation step to compile Kotlin source files */
    protected fun compileKotlin(sources: List<File>, compiler: CLICompiler<A>, arguments: A): KotlinCompilation.ExitCode {

        /**
         * Here the list of compiler plugins is set
         *
         * To avoid that the annotation processors are executed twice,
         * the list is set to empty
         */
        MainComponentRegistrar.threadLocalParameters.set(
            MainComponentRegistrar.ThreadLocalParameters(
                listOf(),
                KaptOptions.Builder(),
                compilerPlugins
            )
        )

        // if no Kotlin sources are available, skip the compileKotlin step
        if (sources.none(File::hasKotlinFileExtension))
            return KotlinCompilation.ExitCode.OK

        // in this step also include source files generated by kapt in the previous step
        val args = arguments.also { args ->
            args.freeArgs = sources.map(File::getAbsolutePath).distinct()
            args.pluginClasspaths = (args.pluginClasspaths ?: emptyArray()) + arrayOf(getResourcesPath())
        }

        val compilerMessageCollector = PrintingMessageCollector(
            internalMessageStream, MessageRenderer.GRADLE_STYLE, verbose
        )

        return convertKotlinExitCode(
            compiler.exec(compilerMessageCollector, Services.EMPTY, args)
        )
    }

    protected fun getResourcesPath(): String {
        val resourceName = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar"
        return this::class.java.classLoader.getResources(resourceName)
            .asSequence()
            .mapNotNull { url ->
                val uri = URI.create(url.toString().removeSuffix("/$resourceName"))
                when (uri.scheme) {
                    "jar" -> Paths.get(URI.create(uri.schemeSpecificPart.removeSuffix("!")))
                    "file" -> Paths.get(uri)
                    else -> return@mapNotNull null
                }.toAbsolutePath()
            }
            .find { resourcesPath ->
                ServiceLoaderLite.findImplementations(ComponentRegistrar::class.java, listOf(resourcesPath.toFile()))
                    .any { implementation -> implementation == MainComponentRegistrar::class.java.name }
            }?.toString() ?: throw AssertionError("Could not get path to ComponentRegistrar service from META-INF")
    }

    /** Searches compiler log for known errors that are hard to debug for the user */
    protected fun searchSystemOutForKnownErrors(compilerSystemOut: String) {
        if (compilerSystemOut.contains("No enum constant com.sun.tools.javac.main.Option.BOOT_CLASS_PATH")) {
            warn(
                "${this::class.simpleName} has detected that the compiler output contains an error message that may be " +
                        "caused by including a tools.jar file together with a JDK of version 9 or later. " +
                        if (inheritClassPath)
                            "Make sure that no tools.jar (or unwanted JDK) is in the inherited classpath"
                        else ""
            )
        }

        if (compilerSystemOut.contains("Unable to find package java.")) {
            warn(
                "${this::class.simpleName} has detected that the compiler output contains an error message " +
                        "that may be caused by a missing JDK. This can happen if jdkHome=null and inheritClassPath=false."
            )
        }
    }

    /** Tries to find a file matching the given [regex] in the host process' classpath */
    protected fun findInHostClasspath(hostClasspaths: List<File>, simpleName: String, regex: Regex): File? {
        val jarFile = hostClasspaths.firstOrNull { classpath ->
            classpath.name.matches(regex)
            //TODO("check that jar file actually contains the right classes")
        }

        if (jarFile == null)
            log("Searched host classpaths for $simpleName and found no match")
        else
            log("Searched host classpaths for $simpleName and found ${jarFile.path}")

        return jarFile
    }

    protected val hostClasspaths by lazy { getHostClasspaths() }

    /* This internal buffer and stream is used so it can be easily converted to a string
    that is put into the [Result] object, in addition to printing immediately to the user's
    stream. */
    protected val internalMessageBuffer = Buffer()
    protected val internalMessageStream = PrintStream(
        TeeOutputStream(
            object : OutputStream() {
                override fun write(b: Int) = messageOutputStream.write(b)
                override fun write(b: ByteArray) = messageOutputStream.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = messageOutputStream.write(b, off, len)
                override fun flush() = messageOutputStream.flush()
                override fun close() = messageOutputStream.close()
            },
            internalMessageBuffer.outputStream()
        )
    )

    protected fun log(s: String) {
        if (verbose)
            internalMessageStream.println("logging: $s")
    }

    protected fun warn(s: String) = internalMessageStream.println("warning: $s")
    protected fun error(s: String) = internalMessageStream.println("error: $s")
}

internal fun kotlinDependencyRegex(prefix:String): Regex {
    return Regex("$prefix(-[0-9]+\\.[0-9]+(\\.[0-9]+)?)([-0-9a-zA-Z]+)?\\.jar")
}

/** Returns the files on the classloader's classpath and modulepath */
internal fun getHostClasspaths(): List<File> {
    val classGraph = ClassGraph()
        .enableSystemJarsAndModules()
        .removeTemporaryFilesAfterScan()

    val classpaths = classGraph.classpathFiles
    val modules = classGraph.modules.mapNotNull { it.locationFile }

    return (classpaths + modules).distinctBy(File::getAbsolutePath)
}

internal fun convertKotlinExitCode(code: ExitCode) = when(code) {
    ExitCode.OK -> KotlinCompilation.ExitCode.OK
    ExitCode.INTERNAL_ERROR -> KotlinCompilation.ExitCode.INTERNAL_ERROR
    ExitCode.COMPILATION_ERROR -> KotlinCompilation.ExitCode.COMPILATION_ERROR
    ExitCode.SCRIPT_EXECUTION_ERROR -> KotlinCompilation.ExitCode.SCRIPT_EXECUTION_ERROR
}