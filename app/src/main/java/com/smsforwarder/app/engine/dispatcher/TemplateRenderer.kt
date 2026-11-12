package com.smsforwarder.app.engine.dispatcher

import com.smsforwarder.app.domain.model.SmsMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for rendering customizable SMS message templates with placeholder variables.
 */
object TemplateRenderer {

    const val DEFAULT_TEMPLATE = "{senderNumber}\n{contactName}\n\n{body}\n\n{date}\n{day}\n{time}"

    private val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.US)
    private val dayFormat = SimpleDateFormat("EEEE", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Replaces standard placeholders in the template with actual message metadata.
     * Supports:
     * - Contact Name: {contactName}, {{contactName}}, ${contactName}, {contact_name}, {{contact_name}}, {name}, {{name}}
     * - Sender Number: {senderNumber}, {{senderNumber}}, ${senderNumber}, {sender_number}, {{sender_number}}
     * - Sender: {{sender}}, {sender}, ${sender}, ${PARAM_FROM}, %from, {from}, {{from}}
     * - Body: {{body}}, {body}, ${body}, ${PARAM_BODY}, %text, %body, {message}, {{message}}, ${message}, {text}, {{text}}
     * - SIM: {{sim}}, {sim}, ${sim}, %sim, {{simSlot}}, {simSlot}, ${simSlot}
     * - Carrier: {{carrier}}, {carrier}, ${carrier}, %carrier
     * - Date: {date}, {{date}}, ${date}, %date (e.g., "11 November 2026")
     * - Day: {day}, {{day}}, ${day}, %day (e.g., "Wednesday")
     * - Time: {{time}}, {time}, ${time}, %time (e.g., "20:27:18")
     * - Timestamp: {{timestamp}}, {timestamp}, ${timestamp} (e.g., "2026-11-11 20:27:18")
     * - Hash: {{hash}}, {hash}, ${hash}
     *
     * @param escapeForJson if true, string values are JSON-escaped (quotes, backslashes, newlines)
     */
    fun render(template: String, message: SmsMessage, escapeForJson: Boolean = false): String {
        val simSlotText = if (message.simSlotIndex >= 0) "SIM ${message.simSlotIndex + 1}" else "SIM Unknown"
        val carrierText = message.carrierName ?: "Unknown"

        val messageDate = Date(message.timestampMillis)
        val dateText = dateFormat.format(messageDate)
        val dayText = dayFormat.format(messageDate)
        val timeText = timeFormat.format(messageDate)
        val timestampText = timestampFormat.format(messageDate)

        val rawContactName = message.contactName?.takeIf { it.isNotBlank() } ?: "Unknown Contact"

        val senderVal = if (escapeForJson) escapeJson(message.sender) else message.sender
        val contactNameVal = if (escapeForJson) escapeJson(rawContactName) else rawContactName
        val bodyVal = if (escapeForJson) escapeJson(message.rawBody) else message.rawBody
        val simVal = if (escapeForJson) escapeJson(simSlotText) else simSlotText
        val carrierVal = if (escapeForJson) escapeJson(carrierText) else carrierText
        val dateVal = if (escapeForJson) escapeJson(dateText) else dateText
        val dayVal = if (escapeForJson) escapeJson(dayText) else dayText
        val timeVal = if (escapeForJson) escapeJson(timeText) else timeText
        val timestampVal = if (escapeForJson) escapeJson(timestampText) else timestampText
        val hashVal = if (escapeForJson) escapeJson(message.deduplicationHash) else message.deduplicationHash

        var result = template

        // Replace Contact Name
        val contactTokens = listOf("{{contactName}}", "{contactName}", "\${contactName}", "{{contact_name}}", "{contact_name}", "{{name}}", "{name}")
        for (token in contactTokens) {
            result = result.replace(token, contactNameVal)
        }

        // Replace Sender Number (run before {sender} to avoid partial token collision)
        val senderNumberTokens = listOf("{{senderNumber}}", "{senderNumber}", "\${senderNumber}", "{{sender_number}}", "{sender_number}")
        for (token in senderNumberTokens) {
            result = result.replace(token, senderVal)
        }

        // Replace Sender
        val senderTokens = listOf("{{sender}}", "{sender}", "\${sender}", "\${PARAM_FROM}", "%from", "{from}", "{{from}}")
        for (token in senderTokens) {
            result = result.replace(token, senderVal)
        }

        // Replace Body / Message
        val bodyTokens = listOf("{{body}}", "{body}", "\${body}", "\${PARAM_BODY}", "%text", "%body", "{message}", "{{message}}", "\${message}", "{text}", "{{text}}")
        for (token in bodyTokens) {
            result = result.replace(token, bodyVal)
        }

        // Replace SIM Slot
        val simTokens = listOf("{{sim}}", "{sim}", "\${sim}", "%sim", "{{simSlot}}", "{simSlot}", "\${simSlot}")
        for (token in simTokens) {
            result = result.replace(token, simVal)
        }

        // Replace Carrier
        val carrierTokens = listOf("{{carrier}}", "{carrier}", "\${carrier}", "%carrier")
        for (token in carrierTokens) {
            result = result.replace(token, carrierVal)
        }

        // Replace Date
        val dateTokens = listOf("{{date}}", "{date}", "\${date}", "%date")
        for (token in dateTokens) {
            result = result.replace(token, dateVal)
        }

        // Replace Day
        val dayTokens = listOf("{{day}}", "{day}", "\${day}", "%day")
        for (token in dayTokens) {
            result = result.replace(token, dayVal)
        }

        // Replace Timestamp (run before {time} to prevent partial token collision)
        val timestampTokens = listOf("{{timestamp}}", "{timestamp}", "\${timestamp}")
        for (token in timestampTokens) {
            result = result.replace(token, timestampVal)
        }

        // Replace Time
        val timeTokens = listOf("{{time}}", "{time}", "\${time}", "%time")
        for (token in timeTokens) {
            result = result.replace(token, timeVal)
        }

        // Replace Hash
        val hashTokens = listOf("{{hash}}", "{hash}", "\${hash}")
        for (token in hashTokens) {
            result = result.replace(token, hashVal)
        }

        return result
    }

    private fun escapeJson(value: String): String {
        val out = StringBuilder()
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> {
                    if (c.code in 0x00..0x1F) {
                        out.append(String.format("\\u%04x", c.code))
                    } else {
                        out.append(c)
                    }
                }
            }
        }
        return out.toString()
    }

    /**
     * Formats SMS metadata using the default standard multi-line layout.
     */
    fun formatDefaultText(message: SmsMessage): String {
        return render(DEFAULT_TEMPLATE, message)
    }

    data class TemplatePreset(
        val name: String,
        val description: String,
        val template: String
    )

    val PRESETS = listOf(
        TemplatePreset(
            name = "Standard",
            description = "Default standard format: sender number, contact, body & date/day/time",
            template = DEFAULT_TEMPLATE
        ),
        TemplatePreset(
            name = "Double-Box Border",
            description = "Unicode double-box framed card layout",
            template = "╔══════════════════════════════════════╗\n" +
                    "║ From: {senderNumber}\n" +
                    "║ Name: {contactName}\n" +
                    "╠──────────────────────────────────────╣\n" +
                    "║ {body}\n" +
                    "╠──────────────────────────────────────╣\n" +
                    "║ {date} • {day} • {time}\n" +
                    "╚══════════════════════════════════════╝"
        ),
        TemplatePreset(
            name = "Rounded Card Border",
            description = "Smooth rounded card styling with status header",
            template = "╭── 📩 NEW SMS ────────────────────────╮\n" +
                    "│ From: {senderNumber} ({contactName})\n" +
                    "│ SIM: {sim}\n" +
                    "├──────────────────────────────────────┤\n" +
                    "│ {body}\n" +
                    "├──────────────────────────────────────┤\n" +
                    "│ 🗓 {date} | {day} | {time}\n" +
                    "╰──────────────────────────────────────╯"
        ),
        TemplatePreset(
            name = "Minimalist Dividers",
            description = "Clean modern dividers with bold accent lines",
            template = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                    "From: {senderNumber} ({contactName})\n" +
                    "----------------------------------------\n" +
                    "{body}\n" +
                    "----------------------------------------\n" +
                    "{date} • {day} • {time}\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        ),
        TemplatePreset(
            name = "Security Alert Badge",
            description = "Heavy bordered alert badge for security and OTP verification",
            template = "┏━ 🚨 SECURITY ALERT ━━━━━━━━━━━━━━━━━━┓\n" +
                    "┃ From: {senderNumber} [{contactName}]\n" +
                    "┃ SIM: {sim}\n" +
                    "┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫\n" +
                    "┃ {body}\n" +
                    "┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫\n" +
                    "┃ Time: {time} | {date}\n" +
                    "┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛"
        ),
        TemplatePreset(
            name = "Modern Minimalist Card",
            description = "Clean single-bracket card layout for chat feeds",
            template = "┌── 📩 NEW SMS ────────────────────────\n" +
                    "│ From: {senderNumber} ({contactName})\n" +
                    "│\n" +
                    "│ {body}\n" +
                    "│\n" +
                    "└── 🗓 {date} | {day} | {time}"
        ),
        TemplatePreset(
            name = "Compact",
            description = "Single-line with SIM slot, sender number, and contact",
            template = "[{sim}] {senderNumber} ({contactName}): {body}"
        ),
        TemplatePreset(
            name = "OTP Alert",
            description = "High-priority security verification alert",
            template = "🚨 OTP / Security Alert [{sim}]\nFrom: {senderNumber} ({contactName})\nTime: {time} | {date}\n\n{body}"
        ),
        TemplatePreset(
            name = "JSON API",
            description = "Structured JSON payload for custom backend APIs",
            template = "{\"sender\": \"{senderNumber}\", \"contact\": \"{contactName}\", \"body\": \"{body}\", \"sim\": \"{sim}\", \"date\": \"{date}\", \"time\": \"{time}\"}"
        ),
        TemplatePreset(
            name = "Discord Webhook",
            description = "Formatted Markdown for Discord channel webhooks",
            template = "{\"content\": \"📩 **SMS from {senderNumber}** ({contactName}) [{sim}]:\\n> {body}\\n*Date: {date} {time}*\"}"
        ),
        TemplatePreset(
            name = "Slack Webhook",
            description = "Formatted block text for Slack incoming webhooks",
            template = "{\"text\": \"📩 *SMS from {senderNumber}* ({contactName}) [{sim}]:\\n>{body}\\n_{date} {time}_\"}"
        ),
        TemplatePreset(
            name = "Telegram Markdown",
            description = "Clean markdown layout for Telegram chats",
            template = "*📬 New SMS Alert* [SIM {sim}]\n*From:* `{senderNumber}` ({contactName})\n*Time:* {date} • {day} • {time}\n\n{body}"
        ),
        TemplatePreset(
            name = "Cloudflare Relay",
            description = "Standard relay payload format",
            template = "{\"sender\": \"{senderNumber}\", \"contact\": \"{contactName}\", \"message\": \"{body}\", \"timestamp\": \"{timestamp}\"}"
        )
    )
}
