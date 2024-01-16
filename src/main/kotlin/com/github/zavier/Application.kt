package com.github.zavier

import com.github.zavier.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

// common：公共代码；
// config：配置服务器；
// push：推送服务；
// quotation：行情服务；
// trading-api：交易API服务；
// trading-engine：交易引擎；
// trading-sequencer：定序服务；
// ui：用户Web界面。
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureRouting()
}
