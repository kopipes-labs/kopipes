package org.myorg.payments

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class PaymentRequestTest {

    private val body = """{"from":{"kind":"checking","account":"C-1"},"to":{"kind":"card","account":"K-2"},"amount":"100","currency":"USD"}"""

    private fun field(id: String = "p-7", body: String?): String? =
        (validate(id, "u-42", body) as Validation.Invalid).error.field

    @Test
    fun `a valid request becomes the payment to be, with a two decimal amount`() {
        // Arrange and act
        val validation = validate("p-7", "u-42", body)

        // Assert
        validation.shouldBeInstanceOf<Validation.Valid>()
        validation.request.amount shouldBe "100.00"
        validation.request.customerId shouldBe "u-42"
    }

    @Test
    fun `unknown fields are ignored, and so are id, customerId and submittedAt in the body`() {
        // Arrange
        val extra = body.dropLast(1) + ""","id":"other","customerId":"u-1","submittedAt":5,"note":"x"}"""

        // Act
        val validation = validate("p-7", "u-42", extra)

        // Assert
        validation.shouldBeInstanceOf<Validation.Valid>()
        validation.request.id shouldBe "p-7"
        validation.request.customerId shouldBe "u-42"
    }

    @Test
    fun `only the request's shape is checked, and the culprit is named`() {
        field(body = "not json") shouldBe null
        field(body = body.replace("checking", "savings")) shouldBe "from"
        field(body = body.replace("\"card\"", "\"bank\"")) shouldBe "to"
        field(body = body.replace("\"100\"", "\"0\"")) shouldBe "amount"
        field(body = body.replace("\"100\"", "\"-5\"")) shouldBe "amount"
        field(body = body.replace("\"100\"", "100.5")) shouldBe null
        field(body = body.replace("USD", "usd")) shouldBe "currency"
        field(id = "bad id!", body = body) shouldBe "id"
    }

    @Test
    fun `the same request has the same hash, another body another`() {
        // Arrange
        val first = (validate("p-7", "u-42", body) as Validation.Valid).request
        val again = (validate("p-7", "u-42", body.replace("\"100\"", "\"100.00\"")) as Validation.Valid).request
        val other = (validate("p-7", "u-42", body.replace("\"100\"", "\"200\"")) as Validation.Valid).request

        // Act and assert
        again.hash shouldBe first.hash
        (other.hash == first.hash) shouldBe false
    }
}
