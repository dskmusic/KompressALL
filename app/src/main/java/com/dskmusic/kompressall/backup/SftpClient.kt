package com.dskmusic.kompressall.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.FileSystemFile
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.Closeable
import java.io.File
import java.security.Security

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

    fun upload(localFile: File, remoteDir: String) {
        mkdirs(remoteDir)
        sftp.put(FileSystemFile(localFile), "$remoteDir/${localFile.name}")
    }

    fun listDirs(path: String): List<RemoteDir> {
        val base = path.trim().ifBlank { "." }
        return sftp.ls(base)
            .filter { it.isDirectory && it.name != "." && it.name != ".." }
            .map { RemoteDir(it.name, if (base == ".") it.name else "$base/${it.name}") }
            .sortedBy { it.name.lowercase() }
    }

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
}
