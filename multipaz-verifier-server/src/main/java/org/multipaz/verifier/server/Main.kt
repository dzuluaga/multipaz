package org.multipaz.verifier.server

import io.ktor.server.application.install
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import org.multipaz.server.ServerConfiguration
import org.multipaz.server.serverHost
import org.multipaz.server.serverPort
import org.multipaz.util.Logger

/**
 * Main entry point to launch the server.
 *
 * Build and start the server using
 *
 * ```./gradlew multipaz-verifier-server:run```
 */
class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val configuration = ServerConfiguration(args)
            val jdbc = configuration.getValue("database_connection")
            if (jdbc != null) {
                if (jdbc.startsWith("jdbc:mysql:")) {
                    Logger.i("Main", "SQL driver: ${com.mysql.cj.jdbc.Driver()}")
                } else if (jdbc.startsWith("jdbc:postgresql:")) {
                    Logger.i("Main", "SQL driver: ${org.postgresql.Driver()}")
                }
            }
            val host = configuration.serverHost ?: "0.0.0.0"
            embeddedServer(Netty, port = configuration.serverPort, host = host, module = {
                install(CallLogging)
                
                // Add helpful logging after server starts
                environment.monitor.subscribe(ApplicationStarted) {
                    Logger.i("Main", "=".repeat(60))
                    Logger.i("Main", "🚀 Verifier Server started successfully!")
                    Logger.i("Main", " Bound to: http://$host:${configuration.serverPort}")
                    Logger.i("Main", "🔗 Access via: http://localhost:${configuration.serverPort}")
                    Logger.w("Main", "⚠️  IMPORTANT: Digital Credentials API requires localhost (not IP addresses)")
                    Logger.w("Main", "   Use http://localhost:${configuration.serverPort} for testing the API")
                    Logger.i("Main", "=".repeat(60))
                }
                
                configureRouting(configuration)
            }).start(wait = true)
        }
    }
}