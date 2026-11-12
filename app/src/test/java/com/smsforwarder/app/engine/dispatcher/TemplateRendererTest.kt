package com.smsforwarder.app.engine.dispatcher

import com.smsforwarder.app.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TemplateRendererTest {

    private val testTimestamp = 1731175200000L // 2024-11-09T18:00:00Z approx

    private val testMessage = SmsMessage(
        id = "sms-100",
        sender = "+15551234567",
        rawBody = "Your verification code is 492810. Do not share.",
        timestampMillis = testTimestamp,
        simSlotIndex = 0,
        subId = 1,
        carrierName = "T-Mobile",
        deduplicationHash = "hash1234567890abcdef",
        createdAt = testTimestamp,
        contactName = "Alice Smith"
    )

    private val expectedDate: String = SimpleDateFormat("dd MMMM yyyy", Locale.US).format(Date(testTimestamp))
    private val expectedDay: String = SimpleDateFormat("EEEE", Locale.US).format(Date(testTimestamp))
    private val expectedTime: String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(testTimestamp))

    @Test
    fun render_replacesAllPlaceholders() {
        val template = "From: {{sender}} | Name: {{contactName}} | SIM: {{sim}} | Carrier: {{carrier}} | Code: {{body}}"
        val rendered = TemplateRenderer.render(template, testMessage)

        assertTrue(rendered.contains("From: +15551234567"))
        assertTrue(rendered.contains("Name: Alice Smith"))
        assertTrue(rendered.contains("SIM: SIM 1"))
        assertTrue(rendered.contains("Carrier: T-Mobile"))
        assertTrue(rendered.contains("Code: Your verification code is 492810. Do not share."))
    }

    @Test
    fun render_contactName_resolvesCorrectlyWhenPresent() {
        val template = "{contactName} ({senderNumber})"
        val rendered = TemplateRenderer.render(template, testMessage)

        assertEquals("Alice Smith (+15551234567)", rendered)
    }

    @Test
    fun render_contactName_fallsBackToUnknownContactWhenNullOrBlank() {
        val msgWithoutContact = testMessage.copy(contactName = null)
        val template = "{contactName} - {senderNumber}"
        val rendered = TemplateRenderer.render(template, msgWithoutContact)

        assertEquals("Unknown Contact - +15551234567", rendered)

        val msgWithBlankContact = testMessage.copy(contactName = "   ")
        val renderedBlank = TemplateRenderer.render(template, msgWithBlankContact)
        assertEquals("Unknown Contact - +15551234567", renderedBlank)
    }

    @Test
    fun render_temporalTokens_formatsDateDayTimeAccurately() {
        val template = "Date: {date} | Day: {day} | Time: {time}"
        val rendered = TemplateRenderer.render(template, testMessage)

        assertEquals("Date: $expectedDate | Day: $expectedDay | Time: $expectedTime", rendered)
    }

    @Test
    fun render_defaultTemplate_matchesExactSpecification() {
        val expected = "${testMessage.sender}\n" +
                "${testMessage.contactName}\n\n" +
                "${testMessage.rawBody}\n\n" +
                "$expectedDate\n" +
                "$expectedDay\n" +
                expectedTime

        val rendered = TemplateRenderer.render(TemplateRenderer.DEFAULT_TEMPLATE, testMessage)
        assertEquals(expected, rendered)
    }

    @Test
    fun formatDefaultText_matchesDefaultStandardTemplate() {
        val formatted = TemplateRenderer.formatDefaultText(testMessage)
        val expected = TemplateRenderer.render(TemplateRenderer.DEFAULT_TEMPLATE, testMessage)
        assertEquals(expected, formatted)

        assertTrue(formatted.contains("+15551234567"))
        assertTrue(formatted.contains("Alice Smith"))
        assertTrue(formatted.contains("Your verification code is 492810"))
        assertTrue(formatted.contains(expectedDate))
        assertTrue(formatted.contains(expectedDay))
        assertTrue(formatted.contains(expectedTime))
    }

    @Test
    fun render_supportsBothSingleBraceAndDoubleBraceTokens() {
        val singleBrace = "{senderNumber} | {contactName} | {body} | {date} | {day} | {time}"
        val doubleBrace = "{{senderNumber}} | {{contactName}} | {{body}} | {{date}} | {{day}} | {{time}}"

        val renderedSingle = TemplateRenderer.render(singleBrace, testMessage)
        val renderedDouble = TemplateRenderer.render(doubleBrace, testMessage)

        val expected = "+15551234567 | Alice Smith | Your verification code is 492810. Do not share. | $expectedDate | $expectedDay | $expectedTime"
        assertEquals(expected, renderedSingle)
        assertEquals(expected, renderedDouble)
    }

    @Test
    fun render_unknownSimSlot_handlesGracefully() {
        val unknownSimMsg = testMessage.copy(simSlotIndex = -1, carrierName = null)
        val template = "{{sim}} - {{carrier}}"
        val rendered = TemplateRenderer.render(template, unknownSimMsg)

        assertEquals("SIM Unknown - Unknown", rendered)
    }

    @Test
    fun render_supportsParamFromAndParamBodySyntaxes() {
        val template = "{\"sender\": \"\${PARAM_FROM}\", \"message\": \"\${PARAM_BODY}\"}"
        val rendered = TemplateRenderer.render(template, testMessage, escapeForJson = true)

        assertTrue(rendered.contains("\"sender\": \"+15551234567\""))
        assertTrue(rendered.contains("\"message\": \"Your verification code is 492810. Do not share.\""))
    }

    @Test
    fun render_escapesQuotesAndNewlinesWhenJsonEscapingEnabled() {
        val msgWithSpecialChars = testMessage.copy(
            rawBody = "Line 1\nLine 2 with \"quotes\" and \\backslash",
            contactName = "O'Connor \"Boss\""
        )
        val template = "{\"text\": \"{body}\", \"name\": \"{contactName}\"}"
        val rendered = TemplateRenderer.render(template, msgWithSpecialChars, escapeForJson = true)

        assertTrue("Must escape newlines as \\n", rendered.contains("Line 1\\nLine 2"))
        assertTrue("Must escape quotes as \\\"", rendered.contains("\\\"quotes\\\""))
        assertTrue("Must escape backslash as \\\\", rendered.contains("\\\\backslash"))
        assertTrue("Must escape quotes in contact name", rendered.contains("O'Connor \\\"Boss\\\""))
    }

    @Test
    fun presets_renderCleanlyWithoutUnreplacedTokens() {
        val requiredTokens = listOf("{senderNumber}", "{contactName}", "{body}", "{date}", "{day}", "{time}", "{sim}")
        for (preset in TemplateRenderer.PRESETS) {
            val rendered = TemplateRenderer.render(preset.template, testMessage)
            // Ensure no unprocessed template brackets remain for core variables
            for (token in requiredTokens) {
                assertFalse("Preset '${preset.name}' should not retain unprocessed token $token", rendered.contains(token))
            }
        }
    }

    @Test
    fun presets_includeAllRequiredFancyBorderStyles() {
        val presetNames = TemplateRenderer.PRESETS.map { it.name }
        assertTrue("Must include Double-Box Border", presetNames.contains("Double-Box Border"))
        assertTrue("Must include Rounded Card Border", presetNames.contains("Rounded Card Border"))
        assertTrue("Must include Minimalist Dividers", presetNames.contains("Minimalist Dividers"))
        assertTrue("Must include Security Alert Badge", presetNames.contains("Security Alert Badge"))
        assertTrue("Must include Standard", presetNames.contains("Standard"))

        // Verify Double-Box Unicode characters
        val doubleBox = TemplateRenderer.PRESETS.first { it.name == "Double-Box Border" }
        assertTrue(doubleBox.template.contains("╔"))
        assertTrue(doubleBox.template.contains("╚"))
        assertTrue(doubleBox.template.contains("║"))

        // Verify Rounded Card Unicode characters
        val roundedCard = TemplateRenderer.PRESETS.first { it.name == "Rounded Card Border" }
        assertTrue(roundedCard.template.contains("╭"))
        assertTrue(roundedCard.template.contains("╰"))
        assertTrue(roundedCard.template.contains("│"))

        // Verify Minimalist Divider characters
        val dividers = TemplateRenderer.PRESETS.first { it.name == "Minimalist Dividers" }
        assertTrue(dividers.template.contains("━"))
        assertTrue(dividers.template.contains("-"))

        // Verify Security Alert Badge characters
        val alert = TemplateRenderer.PRESETS.first { it.name == "Security Alert Badge" }
        assertTrue(alert.template.contains("┏"))
        assertTrue(alert.template.contains("🚨"))
        assertTrue(alert.template.contains("┛"))
    }
}
