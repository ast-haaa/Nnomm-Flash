package com.nnomm

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class NnommApplication

fun main(args: Array<String>) {
    runApplication<NnommApplication>(*args)
}
