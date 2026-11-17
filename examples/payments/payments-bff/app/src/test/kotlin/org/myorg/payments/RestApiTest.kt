package org.myorg.payments

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import io.kopipes.aws.testsupport.TestContext
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class InMemoryPaymentsStore : PaymentsStore {
    val records = mutableMapOf<String, PaymentRecord>()

    override suspend fun find(id: String) = records[id]

    override suspend fun submit(request: ValidRequest, submittedAt: Long): SubmitOutcome {
        records[request.id]?.let { return SubmitOutcome.AlreadyExists(it) }
        val record = PaymentRecord(request.toPayment(submittedAt), request.hash)
        records[request.id] = record
        return SubmitOutcome.Accepted(record)
    }

    override suspend fun listByCustomer(customerId: String) =
        records.values.filter { it.payment.customerId == customerId }.sortedByDescending { it.payment.submittedAt }
}

class RestApiTest {

    private val store = InMemoryPaymentsStore()
    private var now = 1000L
    private val api = RestApi { RestApiContainer(store) { now++ } }
    private val json = Json { ignoreUnknownKeys = true }

    private val body = """{"from":{"kind":"checking","account":"C-1"},"to":{"kind":"card","account":"K-2"},"amount":"100.00","currency":"USD"}"""

    private fun call(method: String, path: String, customer: String? = "u-42", body: String? = null) =
        api.handleRequest(
            APIGatewayProxyRequestEvent()
                .withHttpMethod(method)
                .withPath(path)
                .withHeaders(customer?.let { mapOf("X-Customer-Id" to it) } ?: emptyMap())
                .withBody(body),
            TestContext(),
        )

    @Test
    fun `PUT accepts the payment and answers with its view`() {
        // Arrange and act
        val response = call("PUT", "/payments/p-7", body = body)

        // Assert
        response.statusCode shouldBe 202
        val view = json.decodeFromString<PaymentView>(response.body)
        view.status shouldBe Status.SUBMITTED
        view.to shouldBe Party(Party.CARD, "K-2")
        store.records.getValue("p-7").payment.customerId shouldBe "u-42"
    }

    @Test
    fun `a repeated PUT with the same body is the same payment, with another body a conflict`() {
        // Arrange
        call("PUT", "/payments/p-7", body = body)

        // Act
        val again = call("PUT", "/payments/p-7", body = body)
        val other = call("PUT", "/payments/p-7", body = body.replace("100.00", "200.00"))

        // Assert
        again.statusCode shouldBe 202
        other.statusCode shouldBe 409
        other.body shouldContain "\"error\":\"conflict\""
        store.records.getValue("p-7").payment.submittedAt shouldBe 1000L
    }

    @Test
    fun `a malformed request is a 400 that names the field`() {
        // Arrange and act
        val response = call("PUT", "/payments/p-7", body = body.replace("checking", "savings"))

        // Assert
        response.statusCode shouldBe 400
        response.body shouldContain "\"field\":\"from\""
    }

    @Test
    fun `no X-Customer-Id is a 401`() {
        call("GET", "/payments", customer = null).statusCode shouldBe 401
    }

    @Test
    fun `another customer's payment is not found, not forbidden`() {
        // Arrange
        call("PUT", "/payments/p-7", body = body)

        // Act
        val mine = call("GET", "/payments/p-7")
        val theirs = call("GET", "/payments/p-7", customer = "u-99")

        // Assert
        mine.statusCode shouldBe 200
        theirs.statusCode shouldBe 404
    }

    @Test
    fun `GET payments lists the customer's payments, newest first`() {
        // Arrange
        call("PUT", "/payments/p-7", body = body)
        call("PUT", "/payments/p-8", body = body)
        call("PUT", "/payments/p-9", customer = "u-99", body = body)

        // Act
        val response = call("GET", "/payments")

        // Assert
        response.statusCode shouldBe 200
        json.decodeFromString<List<PaymentView>>(response.body).map { it.id } shouldBe listOf("p-8", "p-7")
    }
}
