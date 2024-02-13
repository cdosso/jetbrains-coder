package com.coder.gateway.sdk

import com.coder.gateway.models.WorkspaceAgentModel
import com.coder.gateway.sdk.convertors.InstantConverter
import com.coder.gateway.sdk.ex.AuthenticationResponseException
import com.coder.gateway.sdk.ex.TemplateResponseException
import com.coder.gateway.sdk.ex.WorkspaceResponseException
import com.coder.gateway.sdk.v2.CoderV2RestFacade
import com.coder.gateway.sdk.v2.models.BuildInfo
import com.coder.gateway.sdk.v2.models.CreateWorkspaceBuildRequest
import com.coder.gateway.sdk.v2.models.Template
import com.coder.gateway.sdk.v2.models.User
import com.coder.gateway.sdk.v2.models.Workspace
import com.coder.gateway.sdk.v2.models.WorkspaceBuild
import com.coder.gateway.sdk.v2.models.WorkspaceTransition
import com.coder.gateway.sdk.v2.models.toAgentModels
import com.coder.gateway.services.CoderSettings
import com.coder.gateway.services.CoderSettingsService
import com.coder.gateway.util.CoderHostnameVerifier
import com.coder.gateway.util.coderSocketFactory
import com.coder.gateway.util.coderTrustManagers
import com.coder.gateway.util.getHeaders
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.net.HttpConfigurable
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.ProxySelector
import java.net.URL
import java.time.Instant
import java.util.UUID
import javax.net.ssl.X509TrustManager

@Service(Service.Level.APP)
class CoderRestClientService {
    var isReady: Boolean = false
        private set
    lateinit var me: User
    lateinit var buildVersion: String
    lateinit var client: CoderRestClient

    /**
     * This must be called before anything else. It will authenticate and load
     * information about the current user and the build version.
     *
     * @throws [AuthenticationResponseException] if authentication failed.
     */
    fun initClientSession(url: URL, token: String): User {
        client = DefaultCoderRestClient(url, token)
        me = client.me()
        buildVersion = client.buildInfo().version
        isReady = true
        return me
    }
}

/**
 * Holds proxy information.  Exists only to interface with tests since they
 * cannot create an HttpConfigurable instance.
 */
data class ProxyValues (
    val username: String?,
    val password: String?,
    val useAuth: Boolean,
    val selector: ProxySelector,
)

/**
 * A client instance that hooks into global JetBrains services for default
 * settings.  Exists only so we can use the base client in tests.
 */
class DefaultCoderRestClient(url: URL, token: String) : CoderRestClient(url, token,
    PluginManagerCore.getPlugin(PluginId.getId("com.coder.gateway"))!!.version,
    service<CoderSettingsService>(),
    ProxyValues(HttpConfigurable.getInstance().proxyLogin,
        HttpConfigurable.getInstance().plainProxyPassword,
        HttpConfigurable.getInstance().PROXY_AUTHENTICATION,
        HttpConfigurable.getInstance().onlyBySettingsSelector))

open class CoderRestClient @JvmOverloads constructor(
    var url: URL, var token: String,
    private val pluginVersion: String,
    private val settings: CoderSettings,
    private val proxyValues: ProxyValues? = null,
) {
    private val httpClient: OkHttpClient
    private val retroRestClient: CoderV2RestFacade

    init {
        val gson: Gson = GsonBuilder().registerTypeAdapter(Instant::class.java, InstantConverter()).setPrettyPrinting().create()

        val socketFactory = coderSocketFactory(settings.tls)
        val trustManagers = coderTrustManagers(settings.tls.caPath)
        var builder = OkHttpClient.Builder()

        if (proxyValues != null) {
            builder = builder
                .proxySelector(proxyValues.selector)
                .proxyAuthenticator { _, response ->
                    if (proxyValues.useAuth && proxyValues.username != null && proxyValues.password != null) {
                        val credentials = Credentials.basic(proxyValues.username, proxyValues.password)
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credentials)
                            .build()
                    } else null
                }
        }

        httpClient = builder
            .sslSocketFactory(socketFactory, trustManagers[0] as X509TrustManager)
            .hostnameVerifier(CoderHostnameVerifier(settings.tls.altHostname))
            .addInterceptor { it.proceed(it.request().newBuilder().addHeader("Coder-Session-Token", token).build()) }
            .addInterceptor { it.proceed(it.request().newBuilder().addHeader("User-Agent", "Coder Gateway/${pluginVersion} (${SystemInfo.getOsNameAndVersion()}; ${SystemInfo.OS_ARCH})").build()) }
            .addInterceptor {
                var request = it.request()
                val headers = getHeaders(url, settings.headerCommand)
                if (headers.isNotEmpty()) {
                    val reqBuilder = request.newBuilder()
                    headers.forEach { h -> reqBuilder.addHeader(h.key, h.value) }
                    request = reqBuilder.build()
                }
                it.proceed(request)
            }
            // This should always be last if we want to see previous interceptors logged.
            .addInterceptor(HttpLoggingInterceptor().apply { setLevel(HttpLoggingInterceptor.Level.BASIC) })
            .build()

        retroRestClient = Retrofit.Builder().baseUrl(url.toString()).client(httpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(CoderV2RestFacade::class.java)
    }

    /**
     * Retrieve the current user.
     * @throws [AuthenticationResponseException] if authentication failed.
     */
    fun me(): User {
        val userResponse = retroRestClient.me().execute()
        if (!userResponse.isSuccessful) {
            throw AuthenticationResponseException("Unable to authenticate to $url: code ${userResponse.code()}, ${userResponse.message().ifBlank { "has your token expired?" }}")
        }

        return userResponse.body()!!
    }

    /**
     * Retrieves the available workspaces created by the user.
     * @throws WorkspaceResponseException if workspaces could not be retrieved.
     */
    fun workspaces(): List<Workspace> {
        val workspacesResponse = retroRestClient.workspaces("owner:me").execute()
        if (!workspacesResponse.isSuccessful) {
            throw WorkspaceResponseException("Unable to retrieve workspaces from $url: code ${workspacesResponse.code()}, reason: ${workspacesResponse.message().ifBlank { "no reason provided" }}")
        }

        return workspacesResponse.body()!!.workspaces
    }

    /**
     * Retrieves agents for the specified workspaces.  Since the workspaces
     * response does not include agents when the workspace is off, this fires
     * off separate queries to get the agents for each workspace, just like
     * `coder config-ssh` does (otherwise we risk removing hosts from the SSH
     * config when they are off).
     */
    fun agents(workspaces: List<Workspace>): List<WorkspaceAgentModel> {
        return workspaces.flatMap {
            val resourcesResponse = retroRestClient.templateVersionResources(it.latestBuild.templateVersionID).execute()
            if (!resourcesResponse.isSuccessful) {
                throw WorkspaceResponseException("Unable to retrieve template resources for ${it.name} from $url: code ${resourcesResponse.code()}, reason: ${resourcesResponse.message().ifBlank { "no reason provided" }}")
            }
            it.toAgentModels(resourcesResponse.body()!!)
        }
    }

    fun buildInfo(): BuildInfo {
        val buildInfoResponse = retroRestClient.buildInfo().execute()
        if (!buildInfoResponse.isSuccessful) {
            throw java.lang.IllegalStateException("Unable to retrieve build information for $url, code: ${buildInfoResponse.code()}, reason: ${buildInfoResponse.message().ifBlank { "no reason provided" }}")
        }
        return buildInfoResponse.body()!!
    }

    private fun template(templateID: UUID): Template {
        val templateResponse = retroRestClient.template(templateID).execute()
        if (!templateResponse.isSuccessful) {
            throw TemplateResponseException("Unable to retrieve template with ID $templateID from $url, code: ${templateResponse.code()}, reason: ${templateResponse.message().ifBlank { "no reason provided" }}")
        }
        return templateResponse.body()!!
    }

    fun startWorkspace(workspaceID: UUID, workspaceName: String): WorkspaceBuild {
        val buildRequest = CreateWorkspaceBuildRequest(null, WorkspaceTransition.START, null, null, null, null)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspaceID, buildRequest).execute()
        if (buildResponse.code() != HTTP_CREATED) {
            throw WorkspaceResponseException("Unable to build workspace $workspaceName on $url, code: ${buildResponse.code()}, reason: ${buildResponse.message().ifBlank { "no reason provided" }}")
        }

        return buildResponse.body()!!
    }

    fun stopWorkspace(workspaceID: UUID, workspaceName: String): WorkspaceBuild {
        val buildRequest = CreateWorkspaceBuildRequest(null, WorkspaceTransition.STOP, null, null, null, null)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspaceID, buildRequest).execute()
        if (buildResponse.code() != HTTP_CREATED) {
            throw WorkspaceResponseException("Unable to stop workspace $workspaceName on $url, code: ${buildResponse.code()}, reason: ${buildResponse.message().ifBlank { "no reason provided" }}")
        }

        return buildResponse.body()!!
    }

    fun updateWorkspace(workspaceID: UUID, workspaceName: String, lastWorkspaceTransition: WorkspaceTransition, templateID: UUID): WorkspaceBuild {
        val template = template(templateID)

        val buildRequest = CreateWorkspaceBuildRequest(template.activeVersionID, lastWorkspaceTransition, null, null, null, null)
        val buildResponse = retroRestClient.createWorkspaceBuild(workspaceID, buildRequest).execute()
        if (buildResponse.code() != HTTP_CREATED) {
            throw WorkspaceResponseException("Unable to update workspace $workspaceName on $url, code: ${buildResponse.code()}, reason: ${buildResponse.message().ifBlank { "no reason provided" }}")
        }

        return buildResponse.body()!!
    }
}
