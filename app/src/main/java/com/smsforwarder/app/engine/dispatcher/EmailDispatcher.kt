package com.smsforwarder.app.engine.dispatcher

import com.smsforwarder.app.core.util.AppResult
import com.smsforwarder.app.data.security.SecureVault
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.DestinationSecrets
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Zero-dependency native Kotlin SSLSocket SMTP Client.
 * Supports direct SSL/TLS (port 465) and STARTTLS (port 587) with strict hostname verification,
 * AUTH LOGIN credential authentication, RFC 5322 header generation, and dot-stuffing data streaming.
 */
class EmailDispatcher(
    private val secureVault: SecureVault
) {

    suspend fun dispatch(
        task: DeliveryTask,
        message: SmsMessage,
        rule: ForwardingRule?
    ): DispatchResult = withContext(Dispatchers.IO) {
        val recipient = task.destinationEndpoint.trim()
        if (recipient.isBlank() || !recipient.contains("@")) {
            return@withContext DispatchResult.PermanentFailure("Invalid destination email address: $recipient")
        }

        val vaultResult = secureVault.getSmtpSecrets(task.vaultSecretAlias)
        if (vaultResult !is AppResult.Success) {
            return@withContext DispatchResult.PermanentFailure("SMTP secrets not configured in vault for alias ${task.vaultSecretAlias}")
        }
        val smtpConfig = vaultResult.data

        Timber.i("Dispatching Email task %s to %s via SMTP host %s:%d", task.id, recipient, smtpConfig.host, smtpConfig.port)

        var activeSocket: Socket? = null
        try {
            activeSocket = if (smtpConfig.port == 465 || !smtpConfig.useStartTls) {
                connectDirectTls(smtpConfig.host, smtpConfig.port)
            } else {
                connectStartTls(smtpConfig.host, smtpConfig.port)
            }

            val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(activeSocket.getOutputStream(), StandardCharsets.UTF_8))

            // For Direct TLS, read initial banner and send EHLO
            if (smtpConfig.port == 465 || !smtpConfig.useStartTls) {
                val banner = readSmtpResponse(reader)
                if (banner.code != 220) {
                    throw SmtpException(banner.code, "SMTP banner rejected: ${banner.line}")
                }

                sendSmtpCommand(writer, "EHLO localhost")
                val ehloResp = readSmtpResponse(reader)
                if (ehloResp.code != 250) {
                    throw SmtpException(ehloResp.code, "SMTP EHLO rejected: ${ehloResp.line}")
                }
            }

            // Authenticate using AUTH LOGIN
            authenticateLogin(reader, writer, smtpConfig)

            // Mail envelope transaction
            sendSmtpCommand(writer, "MAIL FROM:<${smtpConfig.fromAddress}>")
            val mailResp = readSmtpResponse(reader)
            if (mailResp.code != 250) {
                throw SmtpException(mailResp.code, "MAIL FROM rejected: ${mailResp.line}")
            }

            sendSmtpCommand(writer, "RCPT TO:<$recipient>")
            val rcptResp = readSmtpResponse(reader)
            if (rcptResp.code != 250) {
                throw SmtpException(rcptResp.code, "RCPT TO rejected: ${rcptResp.line}")
            }

            sendSmtpCommand(writer, "DATA")
            val dataResp = readSmtpResponse(reader)
            if (dataResp.code != 354) {
                throw SmtpException(dataResp.code, "DATA command rejected: ${dataResp.line}")
            }

            // Transmit RFC 5322 formatted email headers and body
            sendEmailData(writer, recipient, message, rule, smtpConfig)

            val commitResp = readSmtpResponse(reader)
            if (commitResp.code != 250) {
                throw SmtpException(commitResp.code, "Message delivery rejected after DATA: ${commitResp.line}")
            }

            // Send QUIT to close session cleanly
            try {
                sendSmtpCommand(writer, "QUIT")
                readSmtpResponse(reader)
            } catch (_: Exception) {
                // Ignore QUIT response error
            }

            DispatchResult.Success("Email delivered successfully to $recipient via ${smtpConfig.host}")
        } catch (e: SmtpException) {
            when (e.code) {
                in 400..499 -> DispatchResult.RetryableFailure("SMTP transient error ${e.code}: ${e.message}")
                in 500..599 -> DispatchResult.PermanentFailure("SMTP permanent error ${e.code}: ${e.message}")
                else -> DispatchResult.PermanentFailure("SMTP unexpected status ${e.code}: ${e.message}")
            }
        } catch (e: SSLPeerUnverifiedException) {
            DispatchResult.PermanentFailure("SMTP TLS verification failed (hostname mismatch): ${e.message}")
        } catch (e: SSLHandshakeException) {
            DispatchResult.PermanentFailure("SMTP SSL handshake failed: ${e.message}")
        } catch (e: SocketTimeoutException) {
            DispatchResult.RetryableFailure("SMTP socket timeout: ${e.message}")
        } catch (e: UnknownHostException) {
            DispatchResult.RetryableFailure("SMTP DNS resolution failed for ${smtpConfig.host}: ${e.message}")
        } catch (e: IOException) {
            DispatchResult.RetryableFailure("SMTP network I/O error: ${e.message}")
        } catch (e: Exception) {
            DispatchResult.PermanentFailure("SMTP unexpected failure: ${e.message}")
        } finally {
            try {
                activeSocket?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Establishes a direct implicit SSL/TLS socket connection (Port 465) with strict hostname verification.
     */
    private fun connectDirectTls(host: String, port: Int): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = factory.createSocket() as SSLSocket
        sslSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        sslSocket.soTimeout = SOCKET_TIMEOUT_MS

        val params = sslSocket.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        sslSocket.sslParameters = params
        sslSocket.startHandshake()

        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, sslSocket.session)) {
            throw SSLPeerUnverifiedException("SMTP Hostname verification failed for $host")
        }
        return sslSocket
    }

    /**
     * Establishes a plain TCP socket, executes STARTTLS negotiation, and upgrades to SSLSocket (Port 587).
     */
    private fun connectStartTls(host: String, port: Int): SSLSocket {
        val plainSocket = Socket()
        plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        plainSocket.soTimeout = SOCKET_TIMEOUT_MS

        var reader = BufferedReader(InputStreamReader(plainSocket.getInputStream(), StandardCharsets.UTF_8))
        var writer = BufferedWriter(OutputStreamWriter(plainSocket.getOutputStream(), StandardCharsets.UTF_8))

        // Read 220 banner
        val banner = readSmtpResponse(reader)
        if (banner.code != 220) {
            throw SmtpException(banner.code, "STARTTLS initial banner rejected: ${banner.line}")
        }

        // Send initial EHLO
        sendSmtpCommand(writer, "EHLO localhost")
        val ehlo1 = readSmtpResponse(reader)
        if (ehlo1.code != 250) {
            throw SmtpException(ehlo1.code, "STARTTLS initial EHLO rejected: ${ehlo1.line}")
        }

        // Request STARTTLS upgrade
        sendSmtpCommand(writer, "STARTTLS")
        val startTlsResp = readSmtpResponse(reader)
        if (startTlsResp.code != 220) {
            throw SmtpException(startTlsResp.code, "STARTTLS negotiation failed: ${startTlsResp.line}")
        }

        // Layer SSLSocket over existing plain TCP socket
        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = sslFactory.createSocket(plainSocket, host, port, true) as SSLSocket
        val params = sslSocket.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        sslSocket.sslParameters = params
        sslSocket.startHandshake()

        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, sslSocket.session)) {
            throw SSLPeerUnverifiedException("SMTP Hostname verification failed for $host during STARTTLS upgrade")
        }

        reader = BufferedReader(InputStreamReader(sslSocket.getInputStream(), StandardCharsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(sslSocket.getOutputStream(), StandardCharsets.UTF_8))

        // Re-issue EHLO over the secure TLS session
        sendSmtpCommand(writer, "EHLO localhost")
        val ehlo2 = readSmtpResponse(reader)
        if (ehlo2.code != 250) {
            throw SmtpException(ehlo2.code, "EHLO after STARTTLS upgrade rejected: ${ehlo2.line}")
        }

        return sslSocket
    }

    /**
     * Executes RFC 4954 AUTH LOGIN authentication handshake.
     */
    private fun authenticateLogin(
        reader: BufferedReader,
        writer: BufferedWriter,
        config: DestinationSecrets.Smtp
    ) {
        sendSmtpCommand(writer, "AUTH LOGIN")
        val authPrompt = readSmtpResponse(reader)
        if (authPrompt.code != 334) {
            throw SmtpException(authPrompt.code, "AUTH LOGIN command rejected: ${authPrompt.line}")
        }

        // Send Base64 Username
        val b64User = Base64.getEncoder().encodeToString(config.username.toByteArray(StandardCharsets.UTF_8))
        sendSmtpCommand(writer, b64User)
        val userPrompt = readSmtpResponse(reader)
        if (userPrompt.code != 334) {
            throw SmtpException(userPrompt.code, "SMTP username rejected: ${userPrompt.line}")
        }

        // Send Base64 Password
        val b64Pass = Base64.getEncoder().encodeToString(config.password.toByteArray(StandardCharsets.UTF_8))
        sendSmtpCommand(writer, b64Pass)
        val passResp = readSmtpResponse(reader)
        if (passResp.code != 235) {
            throw SmtpException(passResp.code, "SMTP authentication failed (invalid credentials): ${passResp.line}")
        }
    }

    /**
     * Streams RFC 5322 compliant message headers and body with dot-stuffing termination.
     */
    private fun sendEmailData(
        writer: BufferedWriter,
        recipient: String,
        message: SmsMessage,
        rule: ForwardingRule?,
        config: DestinationSecrets.Smtp
    ) {
        val simLabel = if (message.simSlotIndex >= 0) " [SIM ${message.simSlotIndex + 1}]" else ""
        val carrierLabel = message.carrierName?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val rawSubject = "SMS Forwarded: From ${message.sender}$simLabel$carrierLabel"

        // RFC 2047 Base64 encoded Subject for non-ASCII safety
        val encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(rawSubject.toByteArray(StandardCharsets.UTF_8)) + "?="

        val rfcDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date(message.timestampMillis))
        val messageId = "<${UUID.randomUUID()}@${config.host}>"

        val bodyText = if (rule?.customTemplate?.isNotBlank() == true) {
            TemplateRenderer.render(rule.customTemplate, message)
        } else {
            TemplateRenderer.formatDefaultText(message)
        }

        // Write RFC 5322 Headers
        writer.write("From: <${config.fromAddress}>\r\n")
        writer.write("To: <$recipient>\r\n")
        writer.write("Subject: $encodedSubject\r\n")
        writer.write("Date: $rfcDate\r\n")
        writer.write("Message-ID: $messageId\r\n")
        writer.write("MIME-Version: 1.0\r\n")
        writer.write("Content-Type: text/plain; charset=UTF-8\r\n")
        writer.write("Content-Transfer-Encoding: 8bit\r\n")
        writer.write("\r\n") // Empty line separates headers from body

        // Write body with RFC 5321 dot-stuffing
        bodyText.lines().forEach { line ->
            if (line.startsWith(".")) {
                writer.write(".") // Dot-stuffing prepend
            }
            writer.write(line)
            writer.write("\r\n")
        }

        // Terminating sequence
        writer.write(".\r\n")
        writer.flush()
    }

    private fun sendSmtpCommand(writer: BufferedWriter, command: String) {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
    }

    /**
     * Reads RFC 5321 multiline SMTP response codes.
     */
    private fun readSmtpResponse(reader: BufferedReader): SmtpResponse {
        val lines = mutableListOf<String>()
        var lastCode = 0
        while (true) {
            val line = reader.readLine() ?: throw EOFException("SMTP connection closed unexpectedly by remote host")
            lines.add(line)
            if (line.length >= 3 && line.substring(0, 3).all { it.isDigit() }) {
                val code = line.substring(0, 3).toInt()
                lastCode = code
                if (line.length == 3 || line[3] == ' ') {
                    break // Space after 3 digits signifies end of multiline response
                } else if (line[3] == '-') {
                    continue // Hyphen indicates more response lines follow
                }
            }
        }
        return SmtpResponse(lastCode, lines.last(), lines)
    }

    private data class SmtpResponse(
        val code: Int,
        val line: String,
        val allLines: List<String>
    )

    private class SmtpException(val code: Int, message: String) : Exception(message)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val SOCKET_TIMEOUT_MS = 15000
    }
}
