package com.nnomm.plugins

object DashboardLoader {
    val html: String by lazy {
        DashboardLoader::class.java.getResourceAsStream("/dashboard.html")?.bufferedReader()?.readText()
            ?: "<h1>Dashboard HTML Not Found</h1>"
    }
}

val DASHBOARD_HTML: String
    get() = DashboardLoader.html
