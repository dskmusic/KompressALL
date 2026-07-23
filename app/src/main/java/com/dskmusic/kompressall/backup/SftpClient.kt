package com.dskmusic.kompressall.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.Closeable
import java.io.File
import java.security.Security
import java.util.EnumSet

data class RemoteDir(val name: String, val path: String)

/**
 * Conexion SFTP abierta y reutilizable para varias operaciones (evita
 * reconectar por cada archivo al subir un lote entero). PromiscuousVerifier
 * porque son destinos de confianza del propio usuario (red local o su VPN),
 * sin infraestructura de fijado de claves de host.
 */
class SftpSession private constructor(private val ssh: SSHClient, private val sftp: SFTPClient) : Closeable {

    fun mkdirs(path: String) {
        if (path.isNotBlank()) sftp.mkdirs(path)
    }

    /** Sube el archivo a golpes de 64 KB, avisando del progreso por bytes (para poder
     *  calcular velocidad/ETA en la UI) en vez de usar el put() de alto nivel de sshj,
     *  que no expone eso de forma sencilla. */
    fun upload(
        localFile: File,
        remoteDir: String,
        onProgress: (transferred: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ) {
        mkdirs(remoteDir)
        val total = localFile.length()
        sftp.open("$remoteDir/${localFile.name}", EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { remoteFile ->
            remoteFile.RemoteFileOutputStream().use { out ->
                localFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var transferred = 0L
                    while (true) {
                        if (isCancelled()) throw java.io.InterruptedIOException("cancelled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        transferred += read
                        onProgress(transferred, total)
                    }
                }
            }
        }
    }

    fun listDirs(path: String): List<RemoteDir> {
        val base = path.trim().ifBlank { "." }
        return sftp.ls(base)
            .filter { it.isDirectory && it.name != "." && it.name != ".." }
            .map { RemoteDir(it.name, if (base == ".") it.name else "$base/${it.name}") }
            .sortedBy { it.name.lowercase() }
    }

    /** Ruta absoluta real (el "." inicial del SFTP suele ser la home del usuario,
     *  no la raiz, y por eso hace falta esto para poder navegar "hacia arriba"). */
    fun canonicalize(path: String): String = sftp.canonicalize(path.trim().ifBlank { "." })

    override fun close() {
        try { sftp.close() } catch (_: Exception) {}
        try { ssh.disconnect() } catch (_: Exception) {}
    }

    companion object {
        init {
            // Necesario para negociar los algoritmos (curve25519, ed25519...) que usan
            // por defecto los servidores OpenSSH modernos. Se quita primero el "BC" que
            // trae Android (una version recortada sin estos algoritmos) para que no
            // tape al de Bouncy Castle completo que acabamos de anadir como dependencia.
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        fun open(host: String, port: Int, username: String, password: String): SftpSession {
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 8000
            ssh.connect(host, port)
            ssh.authPassword(username, password)
            return SftpSession(ssh, ssh.newSFTPClient())
        }
    }
}

object SftpClient {

    suspend fun openSession(host: String, port: Int, username: String, password: String): Result<SftpSession> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(SftpSession.open(host, port, username, password))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Prueba conexion + credenciales, y que la ruta exista o se pueda crear. */
    suspend fun test(host: String, port: Int, username: String, password: String, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                SftpSession.open(host, port, username, password).use { session ->
                    session.mkdirs(remotePath)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun listDirs(host: String, port: Int, username: String, password: String, path: String): Result<List<RemoteDir>> =
        withContext(Dispatchers.IO) {
            try {
                SftpSession.open(host, port, username, password).use { session ->
                    Result.success(session.listDirs(path))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun canonicalize(host: String, port: Int, username: String, password: String, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                SftpSession.open(host, port, username, password).use { session ->
                    Result.success(session.canonicalize(path))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
