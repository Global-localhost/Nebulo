package com.frostnerd.smokescreen.dialog

import android.content.Context
import android.content.DialogInterface
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.frostnerd.dnstunnelproxy.Decision
import com.frostnerd.dnstunnelproxy.DnsServerConfiguration
import com.frostnerd.dnstunnelproxy.DnsServerInformation
import com.frostnerd.encrypteddnstunnelproxy.*
import com.frostnerd.encrypteddnstunnelproxy.tls.AbstractTLSDnsHandle
import com.frostnerd.encrypteddnstunnelproxy.tls.TLS
import com.frostnerd.encrypteddnstunnelproxy.tls.TLSUpstreamAddress
import com.frostnerd.lifecyclemanagement.BaseDialog
import com.frostnerd.smokescreen.R
import com.frostnerd.smokescreen.getPreferences
import com.frostnerd.smokescreen.log
import com.frostnerd.smokescreen.util.preferences.UserServerConfiguration
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.android.synthetic.main.dialog_new_server.*
import kotlinx.android.synthetic.main.dialog_new_server.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.record.Record
import java.lang.Exception
import java.util.concurrent.TimeUnit

/*
 * Copyright (C) 2019 Daniel Wolf (Ch4t4r)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the developer at daniel.wolf@frostnerd.com.
 */
class NewServerDialog(
    context: Context,
    title: String? = null,
    var dnsOverHttps: Boolean,
    onServerAdded: (serverInfo: DnsServerInformation<*>) -> Unit,
    server: UserServerConfiguration? = null
) : BaseDialog(context, context.getPreferences().theme.dialogStyle) {
    companion object {
        fun isValidDoH(s:String): Boolean {
            // It should contain at least one period (or colon for IPv6), either because it's an IP Address or a domain (like test.com). This explicitly prevents local-only domains (like localhost or raspberry)
            return s.trim().let {
                        it.toHttpUrlOrNull() ?: "https://$it".toHttpUrlOrNull()
                    }?.host?.contains(Regex("[.:]")) ?: false
        }

        fun isValidDot(s:String):Boolean {
            return parseDotAddress(s.trim()) != null
        }

        fun parseDotAddress(s:String):TLSUpstreamAddress? {
            return s.takeIf {
                !it.startsWith("http", ignoreCase = true) && it.isNotBlank() // It isn't http(s)
            }?.let {
                "https://$it".toHttpUrlOrNull() // But it has valid format for a URL
            }?.takeIf {
                it.pathSegments.size == 1 && it.pathSegments[0] == "" // But isn't an URL (no path in string)
            }?.takeIf {
              it.host.contains(Regex("[.:]")) // It should contain at least one period (or colon for IPv6), either because it's an IP Address or a domain (like test.com). This explicitly prevents local-only domains (like localhost or raspberry)
            }?.let { url ->
                val port = if(s.indexOf(":").let {
                        it == -1 || s.toCharArray().getOrNull(it + 1)?.isDigit() != true
                    }) 853 else url.port  // Default would treat it as https (port 443)

                // Try to find a matching server in the default list as it'd contain more information than the user provides
                AbstractTLSDnsHandle.waitUntilKnownServersArePopulated { allServer ->
                    allServer.values.filter {
                        it.servers.any { server ->
                            server.address.host ==  url.host && server.address.port == url.port
                        }
                    }
                }.firstOrNull()?.servers?.firstOrNull {
                    it.address.host == url.host
                }?.address ?: TLSUpstreamAddress(url.host, port)
            }
        }
    }

    init {
        val view = layoutInflater.inflate(R.layout.dialog_new_server, null, false)
        setHintAndTitle(view,dnsOverHttps, title)
        setView(view)

        setButton(
            DialogInterface.BUTTON_NEUTRAL, context.getString(android.R.string.cancel)
        ) { _, _ -> }

        setButton(
            DialogInterface.BUTTON_POSITIVE, context.getString(android.R.string.ok)
        ) { _, _ -> }

        setOnShowListener {
            addUrlTextWatcher(primaryServerWrap, primaryServer, false)
            addUrlTextWatcher(secondaryServerWrap, secondaryServer, true)
            serverName.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    serverNameWrap.error = if (s.isNullOrBlank()) context.getString(R.string.error_invalid_servername)
                    else null
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                }
            })
            serverNameWrap.error = context.getString(R.string.error_invalid_servername)
            getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (inputsValid()) {
                    val name = serverName.text.toString()
                    var primary = primaryServer.text.toString().trim()
                    var secondary =
                        if (secondaryServer.text.isNullOrBlank()) null else secondaryServer.text.toString().trim()

                    if (dnsOverHttps && !primary.startsWith("http", ignoreCase = true)) primary = "https://$primary"
                    if (dnsOverHttps && secondary != null && !secondary.startsWith("http", ignoreCase = true)) secondary = "https://$secondary"
                    invokeCallback(name, primary, secondary, onServerAdded)
                    dismiss()
                } else {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(250)
                }
            }
            val spinnerAdapter = ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_item,
                arrayListOf(
                    context.getString(R.string.dialog_serverconfiguration_https),
                    context.getString(R.string.dialog_serverconfiguration_tls)
                )
            )
            spinnerAdapter.setDropDownViewResource(R.layout.item_tasker_action_spinner_dropdown_item)
            serverType.adapter = spinnerAdapter
            serverType.setSelection(if (dnsOverHttps) 0 else 1)
            serverType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    dnsOverHttps = position == 0
                    setHintAndTitle(view, dnsOverHttps, title)
                    primaryServer.text = primaryServer.text
                    secondaryServer.text = secondaryServer.text
                }
            }
            if(server != null) {
                serverName.setText(server.serverInformation.name)
                primaryServer.setText(server.serverInformation.servers[0].address.formatToString())
                if(server.serverInformation.servers.size > 1) {
                    secondaryServer.setText(server.serverInformation.servers[1].address.formatToString())
                }
            }
        }
    }

    private fun setHintAndTitle(view:View, dnsOverHttps: Boolean, titleOverride:String?) {
        if (dnsOverHttps) {
            if(titleOverride == null) setTitle(R.string.dialog_newserver_title_https)
            view.primaryServer.setHint(R.string.dialog_newserver_primaryserver_hint)
            view.secondaryServer.apply {
                if(isFocused || error != null) setHint(R.string.dialog_newserver_secondaryserver_hint)
            }
        }else {
            if(titleOverride == null) setTitle(R.string.dialog_newserver_title_tls)
            view.primaryServer.setHint(R.string.dialog_newserver_primaryserver_hint_dot)
            view.secondaryServer.apply {
                if(isFocused || error != null) setHint(R.string.dialog_newserver_secondaryserver_hint_dot)
            }
        }
        if(titleOverride != null) setTitle(titleOverride)
    }

    private fun invokeCallback(
        name: String,
        primary: String,
        secondary: String?,
        onServerAdded: (DnsServerInformation<*>) -> Unit
    ) {
        if (dnsOverHttps) {
            detectDohServerTypes(name, primary, secondary, onServerAdded)
        } else {
            val serverInfo = mutableListOf<DnsServerConfiguration<TLSUpstreamAddress>>()
            serverInfo.add(
                DnsServerConfiguration(
                    address = parseDotAddress(primary)!!,
                    experimental = false,
                    supportedProtocols = listOf(TLS),
                    preferredProtocol = TLS
                )
            )
            if (!secondary.isNullOrBlank()) serverInfo.add(
                DnsServerConfiguration(
                    address = parseDotAddress(secondary)!!, experimental = false, supportedProtocols = listOf(TLS), preferredProtocol = TLS
                )
            )
            onServerAdded.invoke(
                DnsServerInformation(
                    name,
                    HttpsDnsServerSpecification(
                        Decision.UNKNOWN,
                        Decision.UNKNOWN,
                        Decision.UNKNOWN,
                        Decision.UNKNOWN
                    ),
                    serverInfo,
                    emptyList()
                )
            )
        }
    }

    private fun detectDohServerTypes(
        name: String,
        primary: String,
        secondary: String?,
        onServerAdded: (DnsServerInformation<*>) -> Unit
    ) {
        val defaultRequestType = RequestType.WIREFORMAT_POST to ResponseType.WIREFORMAT
        val addresses = mutableListOf<HttpsUpstreamAddress>()
        addresses.add(createHttpsUpstreamAddress(primary))
        if (!secondary.isNullOrBlank()) addresses.add(createHttpsUpstreamAddress(secondary))

        val dialog = LoadingDialog(
            context,
            R.string.dialog_doh_detect_type_title,
            R.string.dialog_doh_detect_type_message
        )
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(1250, TimeUnit.MILLISECONDS)
            .readTimeout(1250, TimeUnit.MILLISECONDS)
            .build()
        dialog.show()
        GlobalScope.launch {
            val availableTypes = mapOf(
                defaultRequestType,
                RequestType.WIREFORMAT to ResponseType.WIREFORMAT,
                RequestType.PARAMETER_REQUEST to ResponseType.JSON
            )
            val serverInfo = mutableListOf<HttpsDnsServerConfiguration>()
            for (address in addresses) {
                val detectedTypes = mutableListOf<Pair<RequestType, ResponseType>>()
                for (availableType in availableTypes) {
                    try {
                        val response = ServerConfiguration.createSimpleServerConfig(address, availableType.key, availableType.value).query(client = httpClient,
                            question = Question("example.com", Record.TYPE.A))
                        if(response != null && response.responseCode == DnsMessage.RESPONSE_CODE.NO_ERROR) {
                            detectedTypes.add(availableType.toPair())
                        }
                    } catch (ignored:Exception) {

                    }
                }
                if(detectedTypes.isEmpty()) detectedTypes.add(defaultRequestType)
                serverInfo.add(HttpsDnsServerConfiguration(
                    address = address,
                    experimental = false,
                    requestTypes = detectedTypes.toMap()
                ))
            }
            GlobalScope.launch(Dispatchers.Main) {
                dialog.dismiss()
                onServerAdded.invoke(
                    HttpsDnsServerInformation(
                        name,
                        HttpsDnsServerSpecification(
                            Decision.UNKNOWN,
                            Decision.UNKNOWN,
                            Decision.UNKNOWN,
                            Decision.UNKNOWN
                        ),
                        serverInfo,
                        emptyList()
                    )
                )
            }
        }
    }

    private fun createHttpsUpstreamAddress(url: String): HttpsUpstreamAddress {
        context.log("Creating HttpsUpstreamAddress for `$url`")

        val parsedUrl = url.toHttpUrl()
        val host = parsedUrl.host
        val port = parsedUrl.port
        val path = parsedUrl.pathSegments.takeIf {
            it.isNotEmpty() && (it.size > 1 || !it.contains("")) // Non-empty AND contains something other than "" (empty string used when there is no path)
        }?.joinToString(separator = "/")

        return AbstractHttpsDNSHandle.waitUntilKnownServersArePopulated { allServer ->
            if(path != null) emptyList()
            else allServer.values.filter {
                it.servers.any { server ->
                    server.address.host == host && (server.address.port == port)
                }
            }
        }.firstOrNull()?.servers?.firstOrNull {
            it.address.host == host
        }?.address ?: if (path != null) HttpsUpstreamAddress(host, port, path)
        else HttpsUpstreamAddress(host, port)
    }

    private fun addUrlTextWatcher(input: TextInputLayout, editText: TextInputEditText, emptyAllowed: Boolean) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                var valid = (emptyAllowed && s.isBlank())
                valid = valid || (!s.isBlank() && dnsOverHttps && isValidDoH(s.toString()))
                valid = valid || (!s.isBlank() && !dnsOverHttps && isValidDot(s.toString()))

                input.error = when {
                    valid -> {
                        null
                    }
                    dnsOverHttps -> context.getString(R.string.error_invalid_url)
                    else -> context.getString(R.string.error_invalid_host)
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

        })
    }

    private fun inputsValid(): Boolean = serverNameWrap.error == null &&
            primaryServerWrap.error == null &&
            secondaryServerWrap.error == null

    override fun destroy() {}
}