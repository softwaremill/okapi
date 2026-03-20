package com.softwaremill.okapi.spring

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.http.HttpMessageDeliverer
import com.softwaremill.okapi.http.ServiceUrlResolver
import com.softwaremill.okapi.http.httpDeliveryInfo
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Clock

class OutboxEndToEndTest :
    BehaviorSpec({
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        val wiremock = WireMockServer(wireMockConfig().dynamicPort())

        lateinit var store: PostgresOutboxStore
        lateinit var publisher: OutboxPublisher
        lateinit var processor: OutboxProcessor

        beforeSpec {
            postgres.start()
            wiremock.start()

            Database.connect(
                url = postgres.jdbcUrl,
                driver = postgres.driverClassName,
                user = postgres.username,
                password = postgres.password,
            )

            val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
            Liquibase("com/softwaremill/okapi/db/changelog.xml", ClassLoaderResourceAccessor(), db).use { it.update("") }
            connection.close()

            val clock = Clock.systemUTC()
            store = PostgresOutboxStore(clock)
            publisher = OutboxPublisher(store, clock)

            val urlResolver = ServiceUrlResolver { "http://localhost:${wiremock.port()}" }
            val deliverer = HttpMessageDeliverer(urlResolver)
            val entryProcessor = OutboxEntryProcessor(deliverer, RetryPolicy(maxRetries = 3), clock)
            processor = OutboxProcessor(store, entryProcessor)
        }

        afterSpec {
            wiremock.stop()
            postgres.stop()
        }

        beforeEach {
            wiremock.resetAll()
            transaction { exec("TRUNCATE TABLE outbox") }
        }

        given("a message published within a transaction") {
            `when`("the HTTP endpoint returns 200") {
                wiremock.stubFor(
                    post(urlEqualTo("/api/notify"))
                        .willReturn(aResponse().withStatus(200)),
                )

                transaction {
                    publisher.publish(
                        OutboxMessage("order.created", """{"orderId":"abc-123"}"""),
                        httpDeliveryInfo {
                            serviceName = "notification-service"
                            endpointPath = "/api/notify"
                        },
                    )
                }

                transaction { processor.processNext() }

                val requests = wiremock.findAll(postRequestedFor(urlEqualTo("/api/notify")))
                val counts = transaction { store.countByStatuses() }

                then("WireMock receives exactly one POST request") {
                    requests.size shouldBe 1
                }
                then("request body matches the published payload") {
                    requests.first().bodyAsString shouldBe """{"orderId":"abc-123"}"""
                }
                then("entry is marked as DELIVERED") {
                    counts[OutboxStatus.DELIVERED] shouldBe 1L
                }
            }

            `when`("the HTTP endpoint returns 500") {
                wiremock.stubFor(
                    post(urlEqualTo("/api/notify"))
                        .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")),
                )

                transaction {
                    publisher.publish(
                        OutboxMessage("order.created", """{"orderId":"xyz-456"}"""),
                        httpDeliveryInfo {
                            serviceName = "notification-service"
                            endpointPath = "/api/notify"
                        },
                    )
                }

                transaction { processor.processNext() }

                val counts = transaction { store.countByStatuses() }

                then("entry stays PENDING (retriable failure, retries remaining)") {
                    counts[OutboxStatus.PENDING] shouldBe 1L
                }
                then("no DELIVERED entries") {
                    counts[OutboxStatus.DELIVERED] shouldBe 0L
                }
            }

            `when`("the HTTP endpoint returns 400") {
                wiremock.stubFor(
                    post(urlEqualTo("/api/notify"))
                        .willReturn(aResponse().withStatus(400).withBody("Bad Request")),
                )

                transaction {
                    publisher.publish(
                        OutboxMessage("order.created", """{"orderId":"err-789"}"""),
                        httpDeliveryInfo {
                            serviceName = "notification-service"
                            endpointPath = "/api/notify"
                        },
                    )
                }

                transaction { processor.processNext() }

                val counts = transaction { store.countByStatuses() }

                then("entry is immediately FAILED (permanent failure)") {
                    counts[OutboxStatus.FAILED] shouldBe 1L
                }
                then("no PENDING or DELIVERED entries") {
                    counts[OutboxStatus.PENDING] shouldBe 0L
                    counts[OutboxStatus.DELIVERED] shouldBe 0L
                }
            }

            `when`("the endpoint is unreachable") {
                // No stub — WireMock rejects with connection refused on unmapped path
                wiremock.stubFor(
                    post(urlEqualTo("/api/notify"))
                        .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)),
                )

                transaction {
                    publisher.publish(
                        OutboxMessage("order.created", """{"orderId":"net-000"}"""),
                        httpDeliveryInfo {
                            serviceName = "notification-service"
                            endpointPath = "/api/notify"
                        },
                    )
                }

                transaction { processor.processNext() }

                val counts = transaction { store.countByStatuses() }

                then("entry stays PENDING (retriable network failure)") {
                    counts[OutboxStatus.PENDING] shouldBe 1L
                }
            }
        }
    })
