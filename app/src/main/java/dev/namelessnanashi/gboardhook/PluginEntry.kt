package dev.namelessnanashi.gboardhook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.System.loadLibrary
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class PluginEntry : XposedModule() {
    companion object {
        const val SP_FILE_NAME = "GboardinHook"
        const val SP_KEY = "key"
        const val SP_KEY_LOG = "key_log"
        const val TAG = "gboard-hook"
        const val PACKAGE_NAME = "com.google.android.inputmethod.latin"
        const val DAY: Long = 1000 * 60 * 60 * 24
        const val DEFAULT_NUM = 10
        const val DEFAULT_TIME = DAY * 3
    }

    init {
        loadLibrary("dexkit")
    }

    @Volatile
    private var initializedForProcess = false

    @Volatile
    private var attachHandled = false

    private fun getPref(): SharedPreferences? = try {
        getRemotePreferences(SP_FILE_NAME)
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: Throwable) {
        null
    }

    private val clipboardTextSize by lazy {
        getPref()?.getString(SP_KEY, null)?.split(",")?.get(0)?.toIntOrNull()
            ?: DEFAULT_NUM
    }

    private val clipboardTextTime by lazy {
        getPref()?.getString(SP_KEY, null)?.split(",")?.get(1)?.toLongOrNull()
            ?: DEFAULT_TIME
    }

    private val logSwitch by lazy {
        getPref()?.getBoolean(SP_KEY_LOG, false) ?: false
    }

    private fun log(str: String) {
        if (logSwitch) {
            log(Log.INFO, TAG, str)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (initializedForProcess) return

        val packageName = param.packageName
        val classLoader = param.classLoader

        if (packageName != PACKAGE_NAME &&
            getPref()?.getString(SP_KEY, null)?.split(",")?.getOrNull(2)
                ?.equals("true", true) == false
        ) {
            return
        }
        initializedForProcess = true

        tryHook("Application#attach") {
            val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (attachHandled) {
                    return@intercept result
                }
                attachHandled = true
                try {
                    val dexBridge = DexKitBridge.create(classLoader, true)
                    val context = chain.getArg(0) as Context
                    val sp = context.getSharedPreferences(
                        "gboard_hook",
                        Context.MODE_PRIVATE
                    )
                    val spKeyMethod = "SP_KEY_METHOD"
                    val spKeyMethodReadConfig = "SP_KEY_METHOD_READ_CONFIG"
                    val spKeyVersion = "SP_KEY_VERSION"
                    val versionCode = try {
                        val pkgInfo = context.packageManager.getPackageInfo(
                            context.packageName,
                            0
                        )
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pkgInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pkgInfo.versionCode
                        }
                    } catch (_: Exception) {
                        -1
                    }
                    val gboardVersion = sp.getInt(spKeyVersion, -1)
                    val isSameVersion = versionCode == gboardVersion
                    val methodStr = sp.getString(spKeyMethod, null)
                    val dexMethod: DexMethod? = methodStr?.let {
                        try {
                            DexMethod(it)
                        } catch (e: Exception) {
                            log("dexMethod-$it")
                            log(Log.ERROR, TAG, "Parse DexMethod failed", e)
                            null
                        }
                    }
                    (if (isSameVersion && dexMethod != null) {
                        dexMethod
                    } else {
                        val method = findAdapterMethod(dexBridge)
                        if (method != null) {
                            sp.edit {
                                putInt(spKeyVersion, versionCode)
                                putString(spKeyMethod, method.serialize())
                            }
                        }
                        method
                    })?.let {
                        hookAdapter(it, classLoader)
                    }

                    val methodReadConfigStr = sp.getString(spKeyMethodReadConfig, null)
                    val dexMethodReadConfig: DexMethod? = methodReadConfigStr?.let {
                        try {
                            DexMethod(it)
                        } catch (e: Exception) {
                            log("dexMethodReadConfig-$it")
                            log(Log.ERROR, TAG, "Parse DexMethodReadConfig failed", e)
                            null
                        }
                    }
                    (if (isSameVersion && dexMethodReadConfig != null) {
                        dexMethodReadConfig
                    } else {
                        val method = findReadConfigMethod(dexBridge)
                        if (method != null) {
                            sp.edit {
                                putInt(spKeyVersion, versionCode)
                                putString(spKeyMethodReadConfig, method.serialize())
                            }
                        }
                        method
                    })?.let {
                        hookReadConfig(it, classLoader)
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "Init hook failed", t)
                }
                result
            }
        }

        tryHook("com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider#query") { name ->
            val queryMethod = Class.forName(
                "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider",
                false,
                classLoader
            ).getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java
            )
            hook(queryMethod).intercept { chain ->
                log(name)
                val args = chain.args.toMutableList()

                val arg0 = args[0] as Uri
                val arg1 = args[1] as? Array<*>
                val arg2 = args[2]?.toString() ?: ""
                val arg3 = (args[3] as? Array<*>)?.let {
                    if (it.all { item -> item is String }) {
                        @Suppress("UNCHECKED_CAST")
                        (it as Array<String>).copyOf()
                    } else {
                        null
                    }
                }
                val arg4 = args[4]
                log("query, arg0=$arg0, arg1=${arg1?.joinToString()}, arg2=$arg2, arg3=${arg3?.joinToString()}, arg4=$arg4")

                val indexOf = arg2.indexOf("timestamp >= ?")
                if (indexOf != -1) {
                    var indexOfWen = 0
                    StringBuilder(arg2).forEachIndexed { index, c ->
                        if (index >= indexOf) return@forEachIndexed
                        if (c == '?') {
                            indexOfWen++
                        }
                    }

                    val afterTimeStamp = System.currentTimeMillis() - clipboardTextTime
                    arg3?.let {
                        if (indexOfWen in it.indices) {
                            it[indexOfWen] = afterTimeStamp.toString()
                            args[3] = it
                        }
                    }
                    log(
                        "Modified time limit, ${
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                                .format(Date(afterTimeStamp))
                        }"
                    )
                }
                if (arg4 == "timestamp DESC limit 5") {
                    args[4] = "timestamp DESC limit $clipboardTextSize"
                    log("Modified size limit, $clipboardTextSize")
                }

                val result = chain.proceed(args.toTypedArray())
                log("query end, ${(result as? Cursor)?.count ?: -1}")
                result
            }
        }

//        tryHook("SQLiteQuery#query") {
//            findAndHookMethod(
//                "android.database.sqlite.SQLiteDatabase",
//                classLoader,
//                "query",
//                "java.lang.String",
//                "java.lang.String[]",
//                "java.lang.String",
//                "java.lang.String[]",
//                "java.lang.String",
//                "java.lang.String",
//                "java.lang.String",
//                object : XC_MethodHook() {
//                    override fun beforeHookedMethod(param: MethodHookParam) {
//                        val args = param.args
//                        val table = args.first().toString()
//                        if (table == "clips") {
//                            val arg1 = if (param.args[1] != null) {
//                                param.args[1] as Array<*>
//                            } else null
//                            val arg2 = param.args[2].toString()
//                            val arg3 = if (param.args[3] != null) {
//                                param.args[3] as Array<String>
//                            } else null
//                            val arg4 = param.args[4]
//                            val arg5 = param.args[5]
//                            val arg6 = param.args[6]
//                            log("SQLiteQuery#query, arg1=${arg1?.joinToString()}, arg2=$arg2, arg3=${arg3?.joinToString()}, arg4=$arg4, arg5=$arg5, arg6=$arg6")
//                        }
//                    }
//                }
//            )
//        }


//        try {
//            val dexFile = DexFile(lpparam.appInfo.sourceDir)
//            val entries = dexFile.entries()
//            while (entries.hasMoreElements()) {
//                val className = entries.nextElement()
//                try {
//                    val clazz = lpparam.classLoader.loadClass(className)
//                    // Check if getDumpableTag method exists
//                    val method = clazz.declaredMethods.firstOrNull {
//                        it.parameterTypes.contentEquals(
//                            arrayOf(
//                                ImageView::class.java, ImageView::class.java, String::class.java
//                            )
//                        ) && it.returnType == Void.TYPE
//                    } ?: continue
//
//                    // Found candidate class, hook it
//                    log("Hooking candidate: $className")
//                    findAndHookMethod(
//                        clazz,
//                        "E",
//                        object : XC_MethodHook() {
//                            override fun beforeHookedMethod(param: MethodHookParam) {
//                                val x = XposedHelpers.getIntField(param.thisObject, "x")
//                                val p = XposedHelpers.getIntField(param.thisObject, "p")
//                                log("$className#E, x=$x, p=$p")
//                                XposedHelpers.setIntField(
//                                    param.thisObject,
//                                    "p",
//                                    0
//                                )
//                            }
//                        }
//                    )
//                } catch (e: Throwable) {
//                    // Ignore classes that fail to load
//                }
//            }
//        } catch (e: Throwable) {
//            log("Error scanning dex: $e")
//        }
    }

    private fun tryHook(logStr: String, unit: ((name: String) -> Unit)) {
        try {
            unit(logStr)
        } catch (_: NoSuchMethodException) {
            log("NoSuchMethodError--$logStr")
        } catch (_: ClassNotFoundException) {
            log("ClassNotFoundError--$logStr")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Hook failed for $logStr", t)
        }
    }

    private fun findAdapterMethod(bridge: DexKitBridge): DexMethod? {
        val methodData = bridge.findClass {
            matcher {
                usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter")
                superClass {
                    this.classNameMatcher != null
                }
            }
        }.findMethod {
            matcher {
                usingNumbers(5)
            }
        }.singleOrNull()
        if (methodData == null) {
            log("Can't find adapter")
            return null
        }
        return methodData.toDexMethod()
    }

    private fun hookAdapter(dexMethod: DexMethod, classLoader: ClassLoader) {
        val methodName = dexMethod.name
        val className = dexMethod.className
        val tag = "$className#$methodName"
        log(tag)
        tryHook(tag) {
            val method = resolveNoArgMethod(className, classLoader, methodName)
            hook(method).intercept {
                log(tag)
                null
            }
        }
    }

    private fun findReadConfigMethod(bridge: DexKitBridge): DexMethod? {
        val methodData = bridge.findMethod {
            matcher {
                usingStrings("Invalid flag: ")
                returnType("java.lang.Object")
            }
        }.singleOrNull()
        if (methodData == null) {
            log("Can't find ReadConfig")
            return null
        }
        return methodData.toDexMethod()
    }

    /**
     * Hardcode the value of enable_clipboard_entity_extraction, otherwise limit is always 100, and only 5 are retrieved
     */
    private fun hookReadConfig(dexMethod: DexMethod, classLoader: ClassLoader) {
        val methodName = dexMethod.name
        val className = dexMethod.className
        val tag = "$className#$methodName"
        log(tag)
        tryHook(tag) {
            val method = resolveNoArgMethod(className, classLoader, methodName)
            hook(method).intercept { chain ->
                val thisObj = chain.thisObject
                if (thisObj != null) {
                    val name = getFieldAsString(thisObj, "a")
                    if (name == "enable_clipboard_entity_extraction"
                        || name == "enable_clipboard_query_refactoring"
                    ) {
                        return@intercept false
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun resolveNoArgMethod(className: String, classLoader: ClassLoader, methodName: String): Method {
        val clazz = Class.forName(className, false, classLoader)
        return clazz.getDeclaredMethod(methodName).apply {
            isAccessible = true
        }
    }

    private fun getFieldAsString(target: Any, fieldName: String): String {
        return try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)?.toString() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}