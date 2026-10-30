package io.github.joelpasapera.lanshare.core

import io.github.joelpasapera.lanshare.core.console.ConsoleTests
import io.github.joelpasapera.lanshare.core.http.HttpDateTests
import io.github.joelpasapera.lanshare.core.http.HttpParsingTests
import io.github.joelpasapera.lanshare.core.http.UrlPathTests
import io.github.joelpasapera.lanshare.core.importing.ProjectInspectorTests
import io.github.joelpasapera.lanshare.core.importing.ZipExtractorTests
import io.github.joelpasapera.lanshare.core.qr.QrCodeTests
import io.github.joelpasapera.lanshare.core.server.HardeningServerTests
import io.github.joelpasapera.lanshare.core.server.HardeningUnitTests
import io.github.joelpasapera.lanshare.core.server.RobustnessTests
import io.github.joelpasapera.lanshare.core.server.ServerIntegrationTests
import io.github.joelpasapera.lanshare.core.site.DirectoryPageTests
import io.github.joelpasapera.lanshare.core.site.HtmlInjectorTests
import io.github.joelpasapera.lanshare.core.site.RangeAndCacheTests
import io.github.joelpasapera.lanshare.core.site.StaticResolverTests
import io.github.joelpasapera.lanshare.core.support.ArchiveUrlTests
import io.github.joelpasapera.lanshare.core.support.CollectionTests
import io.github.joelpasapera.lanshare.core.support.IdentityTests
import io.github.joelpasapera.lanshare.core.support.JsonTests
import io.github.joelpasapera.lanshare.core.testkit.TestRunner
import kotlin.system.exitProcess

/** Punto de entrada de la batería: `./gradlew :core:check` o directamente con kotlinc. */
fun main() {
    val suites = listOf(
        QrCodeTests(),
        HttpParsingTests(),
        UrlPathTests(),
        HttpDateTests(),
        RangeAndCacheTests(),
        HtmlInjectorTests(),
        StaticResolverTests(),
        DirectoryPageTests(),
        ConsoleTests(),
        JsonTests(),
        CollectionTests(),
        IdentityTests(),
        ArchiveUrlTests(),
        ZipExtractorTests(),
        ProjectInspectorTests(),
        ServerIntegrationTests(),
        RobustnessTests(),
        HardeningUnitTests(),
        HardeningServerTests(),
    )
    exitProcess(TestRunner.run(suites))
}
