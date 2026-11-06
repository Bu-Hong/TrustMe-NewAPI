package hk.kirk.trustme.hooks

import android.content.pm.ApplicationInfo
import dalvik.system.DexFile
import hk.kirk.trustme.XCompat
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.WeakHashMap
import javax.net.ssl.SSLSession

/**
 * OkHttp 2.x / 3.x / 4.x+ CertificatePinner & HostnameVerifier Hook
 *
 * 通过 Application.attach() 的 ClassLoader 调用，确保 Multi-dex 支持
 *
 * Modern API：hook(Executable).intercept(Chain)
 */
object OkHttpHook {

    private val scannedClassLoaders: MutableSet<ClassLoader> =
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())

    private val hookedRelocatedMethods: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    fun hook(classLoader: ClassLoader, appInfo: ApplicationInfo? = null) {
        hookOkHttp2(classLoader)
        hookOkHttp3(classLoader)
        hookOkHttp4(classLoader)
        hookOkHostnameVerifier(classLoader)
        hookFindMatchingPins(classLoader)
        hookRelocatedCertificatePinners(classLoader, appInfo)
    }

    /** OkHttp 2.x — com.squareup.okhttp.CertificatePinner */
    private fun hookOkHttp2(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("com.squareup.okhttp.CertificatePinner")
            val m = cls.getDeclaredMethod("check", String::class.java, List::class.java)
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("okhttp")) return@hookMethod null
                true
            })
            Logger.d("OkHttp 2.x CertificatePinner.check → 已绕过")
        } catch (_: ClassNotFoundException) {
            Logger.d("OkHttp 2.x 未发现，跳过")
        } catch (e: Throwable) {
            Logger.e("OkHttp 2.x Hook 失败", e)
        }
    }

    /** OkHttp 3.x — okhttp3.CertificatePinner */
    private fun hookOkHttp3(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("okhttp3.CertificatePinner")
            val m = cls.getDeclaredMethod("check", String::class.java, List::class.java)
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("okhttp")) return@hookMethod null
                null
            })
            Logger.d("OkHttp 3.x CertificatePinner.check → 已绕过")
        } catch (_: ClassNotFoundException) {
            Logger.d("OkHttp 3.x 未发现，跳过")
        } catch (e: Throwable) {
            Logger.e("OkHttp 3.x Hook 失败", e)
        }
    }

    /** OkHttp 4.2+ (Kotlin) — check$okhttp 方法 */
    private fun hookOkHttp4(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("okhttp3.CertificatePinner")
            val m = cls.getDeclaredMethod(
                "check\$okhttp",
                String::class.java,
                classLoader.loadClass("kotlin.jvm.functions.Function0"),
            )
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("okhttp")) return@hookMethod null
                null
            })
            Logger.d("OkHttp 4.2+ check\$okhttp → 已绕过")
        } catch (_: ClassNotFoundException) {
        } catch (_: NoSuchMethodException) {
            Logger.d("OkHttp 4.2+ check\$okhttp 方法不存在，跳过")
        } catch (e: Throwable) {
            Logger.e("OkHttp 4.2+ Hook 失败", e)
        }
    }

    /** OkHostnameVerifier.verify — 两个重载 */
    private fun hookOkHostnameVerifier(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("okhttp3.internal.tls.OkHostnameVerifier")

            // verify(String, SSLSession)
            try {
                val m1 = cls.getDeclaredMethod("verify", String::class.java, SSLSession::class.java)
                XCompat.hookMethod(m1, before = { _, _ ->
                    if (!HookPrefs.isHookActive("okhttp")) return@hookMethod null
                    true
                })
                Logger.d("OkHostnameVerifier.verify(String, SSLSession) → 已绕过")
            } catch (_: NoSuchMethodException) {
            } catch (e: Throwable) {
                Logger.e("OkHostnameVerifier(SSLSession) Hook 失败", e)
            }

            // verify(String, X509Certificate)
            try {
                val m2 = cls.getDeclaredMethod("verify", String::class.java, X509Certificate::class.java)
                XCompat.hookMethod(m2, before = { _, _ ->
                    if (!HookPrefs.isHookActive("okhttp")) return@hookMethod null
                    true
                })
                Logger.d("OkHostnameVerifier.verify(String, X509Certificate) → 已绕过")
            } catch (_: NoSuchMethodException) {
            } catch (e: Throwable) {
                Logger.e("OkHostnameVerifier(X509Certificate) Hook 失败", e)
            }
        } catch (_: ClassNotFoundException) {
        } catch (e: Throwable) {
            Logger.e("OkHostnameVerifier Hook 失败", e)
        }
    }

    /** findMatchingPins — SSLUnpinning 的不同策略：清空 hostname 使查找不到 pin */
    private fun hookFindMatchingPins(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("okhttp3.CertificatePinner")
            val m = cls.getDeclaredMethod("findMatchingPins", String::class.java)
            XCompat.hookMethodArgs(m) { args ->
                if (!HookPrefs.isHookActive("okhttp")) return@hookMethodArgs null
                Logger.d("OkHttp3 findMatchingPins → hostname 已清空")
                arrayOf("")
            }
            Logger.d("OkHttp3 findMatchingPins → hostname 已清空")
        } catch (_: ClassNotFoundException) {
        } catch (_: NoSuchMethodException) {
        } catch (e: Throwable) {
            Logger.e("findMatchingPins Hook 失败", e)
        }
    }

    /**
     * Apps may relocate and obfuscate OkHttp, so the canonical
     * okhttp3.CertificatePinner class name is not always present. The OkHttp
     * pinner shape is still stable enough to detect: a class with a Set field
     * named "pins" and a void check-like method whose first argument is a host
     * and whose second argument is the peer certificate chain or a lazy chain
     * provider.
     */
    private fun hookRelocatedCertificatePinners(classLoader: ClassLoader, appInfo: ApplicationInfo?) {
        if (appInfo == null) return

        if (!HookPrefs.isHookActive("okhttp")) return

        synchronized(scannedClassLoaders) {
            if (!scannedClassLoaders.add(classLoader)) return
        }

        val dexPaths = appDexPaths(appInfo)
        if (dexPaths.isEmpty()) return

        var hookedCount = 0
        for (className in dexPaths.asSequence().flatMap(::dexClassNames)) {
            if (!shouldInspectRelocatedClass(className)) continue

            val clazz = try {
                Class.forName(className, false, classLoader)
            } catch (_: Throwable) {
                continue
            }

            val checkMethods = relocatedCheckMethods(clazz)
            if (!isRelocatedCertificatePinner(clazz, checkMethods)) continue

            checkMethods.forEach { method ->
                if (hookRelocatedCheckMethod(method)) {
                    hookedCount++
                }
            }
        }

        if (hookedCount > 0) {
            Logger.d("Relocated OkHttp CertificatePinner methods → 已绕过 $hookedCount 个")
        }
    }

    private fun appDexPaths(appInfo: ApplicationInfo): List<String> {
        val paths = mutableListOf<String>()
        appInfo.sourceDir?.takeIf { it.isNotBlank() }?.let(paths::add)
        appInfo.splitSourceDirs?.filterTo(paths) { it.isNotBlank() }
        return paths.distinct()
    }

    private fun dexClassNames(dexPath: String): Sequence<String> = sequence {
        val dexFile = try {
            DexFile(dexPath)
        } catch (e: Throwable) {
            Logger.d("无法读取 Dex: $dexPath (${e.message})")
            return@sequence
        }

        try {
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                yield(entries.nextElement())
            }
        } finally {
            try {
                dexFile.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun shouldInspectRelocatedClass(className: String): Boolean {
        if (className == "okhttp3.CertificatePinner") return false
        if (className == "com.squareup.okhttp.CertificatePinner") return false

        return !className.startsWith("android.") &&
            !className.startsWith("androidx.") &&
            !className.startsWith("com.android.") &&
            !className.startsWith("java.") &&
            !className.startsWith("javax.") &&
            !className.startsWith("kotlin.") &&
            !className.startsWith("kotlinx.") &&
            !className.startsWith("sun.")
    }

    private fun isRelocatedCertificatePinner(clazz: Class<*>, checkMethods: List<Method>): Boolean {
        val hasPinsField = try {
            clazz.declaredFields.any { field ->
                field.name == "pins" && Set::class.java.isAssignableFrom(field.type)
            }
        } catch (_: Throwable) {
            false
        }
        return hasPinsField && checkMethods.isNotEmpty()
    }

    private fun relocatedCheckMethods(clazz: Class<*>): List<Method> {
        val methods = try {
            clazz.declaredMethods
        } catch (_: Throwable) {
            return emptyList()
        }

        return methods.filter { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == String::class.java &&
                isCertificateChainParameter(method.parameterTypes[1]) &&
                !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isNative(method.modifiers)
        }
    }

    private fun isCertificateChainParameter(type: Class<*>): Boolean {
        if (List::class.java.isAssignableFrom(type)) return true
        if (type.isPrimitive || type.isArray || type == String::class.java) return false

        val methods = try {
            type.methods
        } catch (_: Throwable) {
            return false
        }

        return methods.any { method ->
            method.parameterTypes.isEmpty() &&
                method.returnType != Void.TYPE &&
                method.declaringClass != Any::class.java &&
                !Modifier.isStatic(method.modifiers)
        }
    }

    private fun hookRelocatedCheckMethod(method: Method): Boolean {
        val key = buildString {
            append(method.declaringClass.name)
            append('#')
            append(method.name)
            append('(')
            append(method.parameterTypes.joinToString(",") { it.name })
            append(')')
        }

        if (!hookedRelocatedMethods.add(key)) return false

        return try {
            method.isAccessible = true
            XCompat.hookMethod(method, before = { _, _ ->
                if (!HookPrefs.isHookActive("okhttp")) return@hookMethod null
                null
            })
            Logger.d("Relocated OkHttp CertificatePinner ${method.declaringClass.name}.${method.name} → 已绕过")
            true
        } catch (e: Throwable) {
            hookedRelocatedMethods.remove(key)
            Logger.d("Relocated OkHttp CertificatePinner ${method.declaringClass.name}.${method.name} Hook 跳过: ${e.message}")
            false
        }
    }
}
