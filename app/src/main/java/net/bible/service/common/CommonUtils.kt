/*
 * Copyright (c) 2020 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
 *
 * This file is part of And Bible (http://github.com/AndBible/and-bible).
 *
 * And Bible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * And Bible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with And Bible.
 * If not, see http://www.gnu.org/licenses/.
 *
 */

package net.bible.service.common

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.Html
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ImageSpan
import android.util.LayoutDirection
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.layoutDirection
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.bible.android.BibleApplication
import net.bible.android.BibleApplication.Companion.application
import net.bible.android.activity.BuildConfig.BuildDate
import net.bible.android.activity.BuildConfig.GitHash
import net.bible.android.activity.R
import net.bible.android.database.WorkspaceEntities
import net.bible.android.database.bookmarks.BookmarkEntities
import net.bible.android.database.json
import net.bible.android.view.activity.ActivityComponent
import net.bible.android.view.activity.DaggerActivityComponent
import net.bible.android.view.activity.StartupActivity
import net.bible.android.view.activity.base.CurrentActivityHolder
import net.bible.android.view.activity.download.DownloadActivity
import net.bible.android.view.activity.page.MainBibleActivity.Companion.mainBibleActivity
import net.bible.service.db.DatabaseContainer
import net.bible.service.download.DownloadManager
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.time.DateUtils
import org.crosswire.common.util.IOUtil
import org.crosswire.common.util.Version
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.spongycastle.util.io.pem.PemReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.security.KeyFactory
import java.security.Signature
import java.util.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

fun htmlToSpan(html: String): Spanned {
    val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
    } else {
        Html.fromHtml(html)
    }
    return spanned
}

const val textDisplaySettingsVideo = "https://youtu.be/rz0zyEK9qBk"
const val pinningHelpVideo = "https://youtu.be/27b1g-D3ibA"
const val bookmarksMyNotesVideo = "https://www.youtube.com/watch?v=ZpZ25uqR_BY&t=90s" // beta 3.4 video
const val studyPadsVideo = "https://www.youtube.com/watch?v=ZpZ25uqR_BY&t=652s" // beta 3.4 video
const val workspacesVideo = "https://youtu.be/rz0zyEK9qBk"
const val beta34introVideo = "https://www.youtube.com/watch?v=ZpZ25uqR_BY" // For 3.4 beta intro
const val speakHelpVideo = "https://youtu.be/_wWnS-pjv2A"
const val automaticSpeakBookmarkingVideo = "https://www.youtube.com/watch?v=1HFXLeTERcs"


val BookmarkEntities.Label.displayName get() =
    when {
        isSpeakLabel -> application.getString(R.string.speak)
        isUnlabeledLabel -> application.getString(R.string.label_unlabelled)
        else -> name
    }

/**
 * @author Martin Denham [mjdenham at gmail dot com]
 */
object CommonUtils {

    private const val COLON = ":"
    private const val DEFAULT_MAX_TEXT_LENGTH = 250
    private const val ELLIPSIS = "..."

	val json = Json {
        ignoreUnknownKeys = true
    }

    private const val TAG = "CommonUtils"
    var isAndroid = true
        private set

    val applicationNameMedium get() = BibleApplication.application.getString(R.string.app_name_medium)

    val applicationVersionName: String
        get() {
            var versionName: String
            try {
                val manager = application.packageManager
                val info = manager.getPackageInfo(application.packageName, 0)
                versionName = info.versionName
            } catch (e: NameNotFoundException) {
                Log.e(TAG, "Error getting package name.", e)
                versionName = "Error"
            }

            return "$versionName#$GitHash (built $BuildDate)"
        }

    val mainVersion: String get() {
        val verFull = applicationVersionName
        val numbers = verFull.split(".")
        return "${numbers[0]}.${numbers[1]}"
    }

    val isRtl get(): Boolean {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == LayoutDirection.RTL
    }

    val mainVersionFloat: Float get() {
        return mainVersion.toFloat()
    }

    val isBeta: Boolean get() {
        val verFull = applicationVersionName
        val ver = verFull.split("#")[0]
        return ver.endsWith("-beta") or ver.endsWith("-alpha")
    }

    val applicationVersionNumber: Int
        get() {
            // TODO we have to change this to Long if we one day will have very long version numbers.
            var versionNumber: Int
            try {
                val manager = application.packageManager
                val info = manager.getPackageInfo(application.packageName, 0)
                versionNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toInt()
                } else info.versionCode
            } catch (e: NameNotFoundException) {
                Log.e(TAG, "Error getting package name.", e)
                versionNumber = -1
            }

            return versionNumber
        }

    private val packageInfo: PackageInfo
        get () {
            val manager = application.packageManager
            return manager.getPackageInfo(application.packageName, 0)
        }

    val isFirstInstall get() = packageInfo.firstInstallTime == packageInfo.lastUpdateTime

    val isSplitVertically: Boolean get() {
        val reverse = mainBibleActivity.windowRepository.workspaceSettings.enableReverseSplitMode
        return if(reverse) !isPortrait else isPortrait
    }

    val isPortrait: Boolean get() {
        val res = CurrentActivityHolder.getInstance().currentActivity?.resources?: BibleApplication.application.resources
        return res.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    val megabytesFree: Long
        get() {
            val bytesAvailable = getFreeSpace(Environment.getExternalStorageDirectory().path)
            val megAvailable = bytesAvailable / 1048576
            Log.d(TAG, "Megs available on internal memory :$megAvailable")
            return megAvailable
        }

    val localePref: String?
        get() = sharedPreferences.getString("locale_pref", null)

    /** get preferences used by User Prefs screen
     *
     * @return
     */
    val sharedPreferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(application.applicationContext)

    val truncatedDate: Date
        get() = DateUtils.truncate(Date(), Calendar.DAY_OF_MONTH)

    /** enable performance adjustments for slow devices
     */
    val isSlowDevice: Boolean
        get() = Runtime.getRuntime().availableProcessors() == 1

    init {
        try {
            if (android.os.Build.ID != null) {
                isAndroid = true
            }
        } catch (cnfe: Exception) {
            isAndroid = false
        }

        println("isAndroid:$isAndroid")
    }

    fun buildActivityComponent(): ActivityComponent {
        return DaggerActivityComponent.builder()
                .applicationComponent(application.applicationComponent)
                .build()
    }

    fun getFreeSpace(path: String): Long {
        val stat = StatFs(path)
        val bytesAvailable = stat.blockSize.toLong() * stat.availableBlocks.toLong()
        Log.d(TAG, "Free space :$bytesAvailable")
        return bytesAvailable
    }

    @JvmOverloads
    fun limitTextLength(text: String?, maxLength: Int = DEFAULT_MAX_TEXT_LENGTH, singleLine: Boolean = false): String? {
        var text = text
        if (text != null) {
            val origLength = text.length

            if (singleLine) {
                // get first line but limit length in case there are no line breaks
                text = StringUtils.substringBefore(text, "\n")
            }

            if (text!!.length > maxLength) {
                // break on a space rather than mid-word
                val cutPoint = text.indexOf(" ", maxLength)
                if (cutPoint >= maxLength) {
                    text = text.substring(0, cutPoint + 1)
                }
            }

            if (text.length != origLength) {
                text += ELLIPSIS
            }
        }
        return text
    }

    fun ensureDirExists(dir: File) {
        if (!dir.exists() || !dir.isDirectory) {
            dir.mkdirs()
        }
    }

    fun deleteDirectory(path: File): Boolean {
        Log.d(TAG, "Deleting directory:" + path.absolutePath)
        if (path.exists()) {
            if (path.isDirectory) {
                val files = path.listFiles()
                for (i in files.indices) {
                    if (files[i].isDirectory) {
                        deleteDirectory(files[i])
                    } else {
                        files[i].delete()
                        Log.d(TAG, "Deleted " + files[i])
                    }
                }
            }
            val deleted = path.delete()
            if (!deleted) {
                Log.w(TAG, "Failed to delete:" + path.absolutePath)
            }
            return deleted
        }
        return false
    }

    fun loadProperties(propertiesFile: File): Properties {
        val properties = Properties()
        if (propertiesFile.exists()) {
            var `in`: FileInputStream? = null
            try {
                `in` = FileInputStream(propertiesFile)
                properties.load(`in`)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading properties", e)
            } finally {
                IOUtil.close(`in`)
            }
        }
        return properties
    }

    fun pause(seconds: Int) {
        pauseMillis(seconds * 1000)
    }

    fun pauseMillis(millis: Int) {
        try {
            Thread.sleep(millis.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error sleeping", e)
        }

    }

    fun getSharedPreference(key: String, defaultValue: String): String? {
        return sharedPreferences.getString(key, defaultValue)
    }

    fun saveSharedPreference(key: String, value: String) {
        sharedPreferences.edit()
                .putString(key, value)
                .apply()
    }

    fun getResourceString(resourceId: Int, vararg formatArgs: Any): String {
        return resources.getString(resourceId, *formatArgs)
    }

    fun getResourceInteger(resourceId: Int): Int {
        return resources.getInteger(resourceId)
    }

    fun getResourceBoolean(resourceId: Int): Boolean {
        return resources.getBoolean(resourceId)
    }

    val resources: Resources get() =
        CurrentActivityHolder.getInstance()?.currentActivity?.resources?: application.resources


    fun getResourceColor(resourceId: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val theme = try {
                mainBibleActivity.theme
            } catch (e: NullPointerException) {
                resources.newTheme().apply {
                    applyStyle(R.style.AppTheme, true)
                }
            }
            resources.getColor(resourceId, theme)
        } else {
            resources.getColor(resourceId)
        }

    fun getResourceDrawable(resourceId: Int, context: Context? = null): Drawable? {
        val theme = try {
            mainBibleActivity.theme
        } catch (e: NullPointerException) {
            resources.newTheme().apply {
                applyStyle(R.style.AppTheme, true)
            }
        }
        return ResourcesCompat.getDrawable(context?.resources?:resources, resourceId, theme)
    }

    fun getTintedDrawable(res: Int, color: Int = R.color.grey_500): Drawable {
        val d = getResourceDrawable(res)!!
        d.mutate().setTint(getResourceColor(color))
        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
        return d
    }

    /**
     * convert dip measurements to pixels
     */
    fun convertDipsToPx(dips: Int): Int {
        // Converts 14 dip into its equivalent px
        val scale = resources.displayMetrics.density
        return (dips * scale + 0.5f).toInt()
    }

    /**
     * convert dip measurements to pixels
     */
    fun convertPxToDips(px: Int): Int {
        val scale = resources.displayMetrics.density
        return Math.round(px / scale)
    }

    /**
     * StringUtils methods only compare with a single char and hence create lots
     * of temporary Strings This method compares with all chars and just creates
     * one new string for each original string. This is to minimise memory
     * overhead & gc.
     *
     * @param str
     * @param removeChars
     * @return
     */
    fun remove(str: String, removeChars: CharArray): String? {
        if (StringUtils.isEmpty(str) || !StringUtils.containsAny(str, *removeChars)) {
            return str
        }

        val r = StringBuilder(str.length)
        // for all chars in string
        for (i in 0 until str.length) {
            val strCur = str[i]

            // compare with all chars to be removed
            var matched = false
            var j = 0
            while (j < removeChars.size && !matched) {
                if (removeChars[j] == strCur) {
                    matched = true
                }
                j++
            }
            // if current char does not match any in the list then add it to the
            if (!matched) {
                r.append(strCur)
            }
        }
        return r.toString()
    }

    /** format seconds duration as h:m:s
     *
     * @param secs duration
     * @return h:m:s
     */
    fun getHoursMinsSecs(secs: Long): String {
        val h = (secs / 3600).toInt()
        val m = (secs / 60 % 60).toInt()
        val s = (secs % 60).toInt()

        val hms = StringBuilder()
        if (h > 0) {
            hms.append(h).append(COLON)
        }

        // add padding for 1 digit mins
        if (m < 10) {
            hms.append(0)
        }
        hms.append(m).append(COLON)

        // add padding for 1 digit secs
        if (s < 10) {
            hms.append(0)
        }
        hms.append(s)
        return hms.toString()
    }

    fun getKeyDescription(key: Key): String {
        var name: String
        try {
            name = key.name

            // do not show verse 0
            if (key is Verse) {
                if (key.verse == 0 && name.endsWith("0")) {
                    val verse0 = "[\\W]0$"
                    name = name.replace(verse0.toRegex(), "")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting key name - could that Versification does not contain book")
            // but this normally works
            name = key.osisRef.replace('.', ' ')
        }

        return name
    }

    fun getWholeChapter(currentVerse: Verse, showIntros: Boolean = true): VerseRange {
        Log.i(TAG, "getWholeChapter (Key) ${currentVerse.osisID}")
        val versification = currentVerse.versification
        val book = currentVerse.book
        val chapter = currentVerse.chapter

        val startChapter = if(showIntros && chapter == 1) 0 else chapter
        val endChapter = if(showIntros && chapter == 0) 1 else chapter

        val targetChapterFirstVerse = Verse(versification, book, startChapter, 0)
        val targetChapterLastVerse = Verse(versification, book, endChapter, versification.getLastVerse(book, endChapter))

        // convert to full chapter before returning because bible view is for a full chapter
        return VerseRange(versification, targetChapterFirstVerse, targetChapterLastVerse)
    }

    fun restartApp(callingActivity: Activity) {
        val intent = Intent(callingActivity, StartupActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        val pendingIntent: PendingIntent
        pendingIntent = PendingIntent.getActivity(callingActivity, 0, intent, 0)

        val mgr = callingActivity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent)
        System.exit(2)
    }

    val lastDisplaySettings: List<WorkspaceEntities.TextDisplaySettings.Types> get() {
        val lastDisplaySettingsString = sharedPreferences.getString("lastDisplaySettings", null)
        var lastTypes = mutableListOf<WorkspaceEntities.TextDisplaySettings.Types>()
        if(lastDisplaySettingsString!= null) {
            try {
                lastTypes = LastTypesSerializer.fromJson(lastDisplaySettingsString).types
            } catch (e: SerializationException) {
                Log.e(TAG, "Could not deserialize $lastDisplaySettingsString")
            }
        }
        return lastTypes
    }

    fun displaySettingChanged(type: WorkspaceEntities.TextDisplaySettings.Types) {
        val lastTypes = lastDisplaySettings.toMutableList()
        lastTypes.remove(type)
        while (lastTypes.size >= 5) {
            lastTypes.removeAt(lastTypes.size - 1)
        }
        lastTypes.add(0, type)
        sharedPreferences.edit().putString("lastDisplaySettings", LastTypesSerializer(lastTypes).toJson()).apply()
    }

    private val docDao get() = DatabaseContainer.db.swordDocumentInfoDao()

    suspend fun unlockDocument(context: Context, book: Book): Boolean {
        class ShowAgain: Exception()
        var repeat = true
        while(repeat) {
            val passphrase: String? = try {suspendCoroutine {
                val name = EditText(context)
                name.text = SpannableStringBuilder(book.unlockKey ?: "")
                name.selectAll()
                name.requestFocus()
                AlertDialog.Builder(context)
                    .setCancelable(false)
                    .setPositiveButton(R.string.okay) { d, _ ->
                        it.resume(name.text.toString())
                    }
                    .setView(name)
                    .setNegativeButton(R.string.cancel) { _, _ -> it.resume(null) }
                    .setNeutralButton(R.string.show_unlock_info) { _, _ -> GlobalScope.launch(Dispatchers.Main) {
                        showAbout(context, book)
                        it.resumeWithException(ShowAgain())
                    } }
                    .setTitle(application.getString(R.string.give_passphrase_for_module, book.initials))
                    .create()
                    .show()
            } } catch (e: ShowAgain) {
                continue
            }
            if (passphrase != null) {
                val success = book.unlock(passphrase)
                if (success) {
                    docDao.getBook(book.initials)?.apply {
                        cipherKey = passphrase
                        docDao.update(this)
                    }
                    return true
                }
            }
            repeat = suspendCoroutine {
                AlertDialog.Builder(context)
                    .setCancelable(false)
                    .setPositiveButton(R.string.yes) { d, _ ->
                        it.resume(true)
                    }
                    .setNegativeButton(R.string.no) { _, _ -> it.resume(false) }
                    .setTitle(application.getString(R.string.try_again_passphrase))
                    .create()
                    .show()
            }
        }
        return false
    }

    /** about display is generic so handle it here
     */
    suspend fun showAbout(context: Context, document: Book) {
        var about = "<b>${document.name}</b>\n\n"
        about += document.bookMetaData.getProperty("About") ?: ""
        // either process the odd formatting chars in about
        about = about.replace("\\pard", "")
        about = about.replace("\\par", "\n")

        val shortPromo = document.bookMetaData.getProperty(SwordBookMetaData.KEY_SHORT_PROMO)

        if(shortPromo != null) {
            about += "\n\n${shortPromo}"
        }

        // Copyright and distribution information
        val shortCopyright = document.bookMetaData.getProperty(SwordBookMetaData.KEY_SHORT_COPYRIGHT)
        val copyright = document.bookMetaData.getProperty(SwordBookMetaData.KEY_COPYRIGHT)
        val distributionLicense = document.bookMetaData.getProperty(SwordBookMetaData.KEY_DISTRIBUTION_LICENSE)
        val unlockInfo = document.bookMetaData.getProperty(SwordBookMetaData.KEY_UNLOCK_INFO)
        var copyrightMerged = ""
        if (StringUtils.isNotBlank(shortCopyright)) {
            copyrightMerged += shortCopyright
        } else if (StringUtils.isNotBlank(copyright)) {
            copyrightMerged += "\n\n" + copyright
        }
        if (StringUtils.isNotBlank(distributionLicense)) {
            copyrightMerged += "\n\n" +distributionLicense
        }
        if (StringUtils.isNotBlank(copyrightMerged)) {
            val copyrightMsg = application.getString(R.string.module_about_copyright, copyrightMerged)
            about += "\n\n" + copyrightMsg
        }
        if(unlockInfo != null) {
            about += "\n\n<b>${application.getString(R.string.unlock_info)}</b>\n\n$unlockInfo"
        }

        // add version
        val existingDocument = Books.installed().getBook(document.initials)
        val existingVersion = existingDocument?.bookMetaData?.getProperty("Version")
        val existingVersionDate = existingDocument?.bookMetaData?.getProperty("SwordVersionDate") ?: "-"

        val inDownloadScreen = context is DownloadActivity

        val versionLatest = document.bookMetaData.getProperty("Version")
        val versionLatestDate = document.bookMetaData.getProperty("SwordVersionDate") ?: "-"

        val versionMessageInstalled = if(existingVersion != null)
            application.getString(R.string.module_about_installed_version, Version(existingVersion).toString(), existingVersionDate)
        else null

        val versionMessageLatest = if(versionLatest != null)
            application.getString((
                if (existingDocument != null)
                    R.string.module_about_latest_version
                else
                    R.string.module_about_installed_version),
                Version(versionLatest).toString(), versionLatestDate)
        else null

        if(versionMessageLatest != null) {
            about += "\n\n" + versionMessageLatest
            if(versionMessageInstalled != null && inDownloadScreen)
                about += "\n" + versionMessageInstalled
        }

        val history = document.bookMetaData.getValues("History")
        if(history != null) {
            about += "\n\n" + application.getString(R.string.about_version_history, "\n" +
                history.reversed().joinToString("\n"))
        }

        // add versification
        if (document is SwordBook) {
            val versification = document.versification
            val versificationMsg = application.getString(R.string.module_about_versification, versification.name)
            about += "\n\n" + versificationMsg
        }

        // add id
        if (document is SwordBook) {
            val repoName = document.getProperty(DownloadManager.REPOSITORY_KEY)
            val repoMessage = if(repoName != null) application.getString(R.string.module_about_repository, repoName) else ""
            val osisIdMessage = application.getString(R.string.module_about_osisId, document.initials)
            about += """


                $osisIdMessage
                
                $repoMessage
                """.trimIndent()
        }
        about = about.replace("\n", "<br>")
        val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(about, Html.FROM_HTML_MODE_LEGACY)
        } else {
            Html.fromHtml(about)
        }
        suspendCoroutine<Any?> {
            val d = AlertDialog.Builder(context)
                .setMessage(spanned)
                .setCancelable(false)
                .setPositiveButton(R.string.okay) { dialog, buttonId ->
                    it.resume(null)
                }.create()
            d.show()
            val textView = d.findViewById<TextView>(android.R.id.message)!!
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.setTextIsSelectable(true)
        }
    }

    fun showHelp(callingActivity: Activity, filterItems: List<Int>? = null, showVersion: Boolean = false) {
        val app = application
        val versionMsg = app.getString(R.string.version_text, CommonUtils.applicationVersionName)

        data class HelpItem(val title: Int, val text: Int, val videoLink: String? = null)

        val help = listOf(
            HelpItem(R.string.help_nav_title, R.string.help_nav_text),
            HelpItem(R.string.help_contextmenus_title, R.string.help_contextmenus_text),
            HelpItem(R.string.help_window_pinning_title, R.string.help_window_pinning_text, pinningHelpVideo),
            HelpItem(R.string.help_bookmarks_title, R.string.help_bookmarks_text, bookmarksMyNotesVideo), // beta video
            HelpItem(R.string.help_studypads_title, R.string.help_studypads_text, studyPadsVideo), // beta video
            HelpItem(R.string.help_search_title, R.string.help_search_text),
            HelpItem(R.string.help_workspaces_title, R.string.help_workspaces_text, workspacesVideo),
            HelpItem(R.string.help_hidden_features_title, R.string.help_hidden_features_text)
        ).run {
            if(filterItems != null) {
                filter { filterItems.contains(it.title) }
            } else this
        }

        var htmlMessage = ""

        for(helpItem in help) {
            val videoMessage =
                if(helpItem.videoLink != null) {
                    "<i><a href=\"${helpItem.videoLink}\">${app.getString(R.string.watch_tutorial_video)}</a></i><br>"
                } else ""

            val helpText = app.getString(helpItem.text).replace("\n", "<br>")
            htmlMessage += "<b>${app.getString(helpItem.title)}</b><br>$videoMessage$helpText<br><br>"
        }
        if(showVersion)
            htmlMessage += "<i>$versionMsg</i>"

        val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(htmlMessage, Html.FROM_HTML_MODE_LEGACY)
        } else {
            Html.fromHtml(htmlMessage)
        }

        val d = androidx.appcompat.app.AlertDialog.Builder(callingActivity)
            .setTitle(R.string.help)
            .setMessage(spanned)
            .setPositiveButton(android.R.string.ok) { _, _ ->  }
            .create()

        d.show()
        d.findViewById<TextView>(android.R.id.message)!!.movementMethod = LinkMovementMethod.getInstance()
    }

    fun verifySignature(file: File, signatureFile: File): Boolean {
        // Adapted from https://stackoverflow.com/questions/34066949/verify-digital-signature-on-android
        val reader = PemReader(InputStreamReader(application.resources.openRawResource(R.raw.publickey)))
        val data = file.inputStream()
        val signatureData = signatureFile.inputStream()

        val publicKeyPem = reader.readPemObject()
        val publicKeyBytes: ByteArray = publicKeyPem.content
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKeySpec = X509EncodedKeySpec(publicKeyBytes)
        val publicKey = keyFactory.generatePublic(publicKeySpec) as RSAPublicKey
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initVerify(publicKey)
        val buffy = ByteArray(16 * 1024)
        var read = -1
        while (data.read(buffy).also { read = it } != -1) {
            signature.update(buffy, 0, read)
        }
        val signatureBytes = ByteArray(publicKey.modulus.bitLength() / 8)
        signatureData.read(signatureBytes)
        return signature.verify(signatureBytes)
    }

    var initialized = false

    fun initializeApp() {
        if(!initialized) {
            docDao.getUnlocked().forEach {
                val book = Books.installed().getBook(it.initials)
                book.unlock(it.cipherKey)
            }

            // IN practice we don't need to restore this data, because it is stored by JSword in book
            // metadata (persisted by JSWORD to files) too.
            //docDao.getAll().forEach {
            //    Books.installed().getBook(it.initials)?.putProperty(REPOSITORY_KEY, it.repository)
            //}

            initialized = true
        }
    }
}

@Serializable
data class LastTypesSerializer(val types: MutableList<WorkspaceEntities.TextDisplaySettings.Types>) {
    fun toJson(): String {
        return json.encodeToString(serializer(), this)
    }

    companion object {
        fun fromJson(jsonString: String): LastTypesSerializer {
            return json.decodeFromString(serializer(), jsonString)
        }
    }
}
