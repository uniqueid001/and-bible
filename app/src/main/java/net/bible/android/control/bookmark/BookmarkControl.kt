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
package net.bible.android.control.bookmark

import android.util.Log
import net.bible.android.activity.R
import net.bible.android.common.resource.ResourceProvider
import net.bible.android.control.ApplicationScope
import net.bible.android.control.event.ABEventBus
import net.bible.android.control.page.DocumentCategory
import net.bible.android.control.page.window.ActiveWindowPageManagerProvider
import net.bible.android.database.bookmarks.BookmarkEntities.Bookmark
import net.bible.android.database.bookmarks.BookmarkEntities.BookmarkToLabel
import net.bible.android.database.bookmarks.BookmarkEntities.Label
import net.bible.android.database.bookmarks.BookmarkEntities.JournalTextEntry
import net.bible.android.database.bookmarks.BookmarkSortOrder
import net.bible.android.database.bookmarks.BookmarkStyle
import net.bible.android.database.bookmarks.PlaybackSettings
import net.bible.android.database.bookmarks.SPEAK_LABEL_NAME
import net.bible.service.common.CommonUtils
import net.bible.service.db.DatabaseContainer
import net.bible.service.sword.SwordContentFacade
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.BibleBook
import java.lang.IndexOutOfBoundsException
import javax.inject.Inject

abstract class BookmarkEvent

class BookmarkAddedOrUpdatedEvent(val bookmark: Bookmark): BookmarkEvent()
class BookmarksDeletedEvent(val bookmarkIds: List<Long>): BookmarkEvent()
class LabelAddedOrUpdatedEvent(val label: Label): BookmarkEvent()

class JournalTextEntryAddedOrUpdatedEvent(
    val journalTextEntry: JournalTextEntry,
    val bookmarkToLabelsOrderChanged: List<BookmarkToLabel>,
    val journalOrderChanged: List<JournalTextEntry>
)

class JournalTextEntryDeleted(val journalId: Long)

const val LABEL_ALL_ID = -999L
const val LABEL_UNLABELED_ID = -998L

@ApplicationScope
open class BookmarkControl @Inject constructor(
	private val activeWindowPageManagerProvider: ActiveWindowPageManagerProvider,
    private val swordContentFacade: SwordContentFacade,
    resourceProvider: ResourceProvider
) {
    // Dummy labels for all / unlabelled
    private val labelAll = Label(LABEL_ALL_ID, resourceProvider.getString(R.string.all)?: "all", color = BookmarkStyle.GREEN_HIGHLIGHT.backgroundColor)
    val labelUnlabelled = Label(LABEL_UNLABELED_ID, resourceProvider.getString(R.string.label_unlabelled)?: "unlabeled", color = BookmarkStyle.BLUE_HIGHLIGHT.backgroundColor)

    private val dao get() = DatabaseContainer.db.bookmarkDao()

	fun updateBookmarkPlaybackSettings(settings: PlaybackSettings) {
        val pageManager = activeWindowPageManagerProvider.activeWindowPageManager
        if (pageManager.currentPage.documentCategory == DocumentCategory.BIBLE) {
            updateBookmarkPlaybackSettings(pageManager.currentBible.singleKey, settings)
        }
    }

    private fun updateBookmarkPlaybackSettings(v: Verse, settings: PlaybackSettings) {
        val verse = if (v.verse == 0) Verse(v.versification, v.book, v.chapter, 1) else v

        val bookmark = dao.bookmarksForVerseStartWithLabel(verse, speakLabel).firstOrNull()
        if (bookmark?.playbackSettings != null) {
            bookmark.playbackSettings = settings
            addOrUpdateBookmark(bookmark)
            Log.d("SpeakBookmark", "Updated bookmark settings " + bookmark + settings.speed)
        }
    }

    val allBookmarks: List<Bookmark> get() = dao.allBookmarks()

    fun allBookmarksWithNotes(orderBy: BookmarkSortOrder): List<Bookmark> = dao.allBookmarksWithNotes(orderBy)

    fun addOrUpdateBookmark(bookmark: Bookmark, labels: List<Long>?=null): Bookmark {
        if(bookmark.id != 0L) {
            dao.update(bookmark)
        } else {
            bookmark.id = dao.insert(bookmark)
        }

        if(labels != null) {
            dao.deleteLabels(bookmark.id)
            dao.insert(labels.filter { it > 0 }.map { BookmarkToLabel(bookmark.id, it) })
        }

        addText(bookmark)
        addLabels(bookmark)
        ABEventBus.getDefault().post(
            BookmarkAddedOrUpdatedEvent(bookmark)
        )
        return bookmark
    }

    fun bookmarksByIds(ids: List<Long>): List<Bookmark> = dao.bookmarksByIds(ids)

    fun hasBookmarksForVerse(verse: Verse): Boolean = dao.hasBookmarksForVerse(verse)

    fun firstBookmarkStartingAtVerse(key: Verse): Bookmark? = dao.bookmarksStartingAtVerse(key).firstOrNull()

    fun deleteBookmark(bookmark: Bookmark) {
        dao.delete(bookmark)
        ABEventBus.getDefault().post(BookmarksDeletedEvent(listOf(bookmark.id)))
    }

    fun deleteBookmarks(bookmarks: List<Bookmark>) {
        dao.deleteBookmarks(bookmarks)
        ABEventBus.getDefault().post(BookmarksDeletedEvent(bookmarks.map { it.id }))
    }

    fun deleteBookmarksById(bookmarkIds: List<Long>) {
        dao.deleteBookmarksById(bookmarkIds)
        ABEventBus.getDefault().post(BookmarksDeletedEvent(bookmarkIds))
    }

    fun getBookmarksWithLabel(label: Label, orderBy: BookmarkSortOrder = BookmarkSortOrder.BIBLE_ORDER, addData: Boolean = false): List<Bookmark> {
        val bookmarks = when {
            labelAll == label -> dao.allBookmarks(orderBy)
            labelUnlabelled == label -> dao.unlabelledBookmarks(orderBy)
            else -> dao.bookmarksWithLabel(label, orderBy)
        }
        if(addData) for (it in bookmarks) {
            addText(it)
            addLabels(it)
        }
        return bookmarks
    }

    fun bookmarksByLabelId(labelId: Long) = dao.bookmarksWithLabel(labelId, BookmarkSortOrder.ORDER_NUMBER)

    fun labelsForBookmark(bookmark: Bookmark): List<Label> {
        return dao.labelsForBookmark(bookmark.id)
    }

    fun setLabelsByIdForBookmark(bookmark: Bookmark, labelIdList: List<Long>) {
        dao.deleteLabels(bookmark)
        dao.insert(labelIdList.filter { it > 0 }.map { BookmarkToLabel(bookmark.id, it) })
        addText(bookmark)
        bookmark.labelIds = labelIdList
        ABEventBus.getDefault().post(BookmarkAddedOrUpdatedEvent(bookmark))
    }

    fun setLabelsForBookmark(bookmark: Bookmark, labels: List<Label>) =
        setLabelsByIdForBookmark(bookmark, labels.map { it.id })

    fun insertOrUpdateLabel(label: Label): Label {
        if(label.id < 0) throw RuntimeException("Illegal negative label.id")
        if(label.id > 0L) {
            dao.update(label)
        } else {
            label.id = dao.insert(label)
        }
        ABEventBus.getDefault().post(LabelAddedOrUpdatedEvent(label))
        return label
    }

    fun deleteLabel(label: Label) = dao.delete(label)

    // add special label that is automatically associated with all-bookmarks
    val allLabels: List<Label>
        get() {
            val labelList = assignableLabels.toMutableList()
            // add special label that is automatically associated with all-bookmarks
            labelList.add(0, labelUnlabelled)
            labelList.add(0, labelAll)
            return labelList
        }

    val assignableLabels: List<Label> get() = dao.allLabelsSortedByName()

    private var _speakLabel: Label? = null
    val speakLabel: Label get() {
        return _speakLabel
            ?: dao.labelById(CommonUtils.sharedPreferences.getLong("speak_label_id", -1))
                ?.also {
                    _speakLabel = it
                }
            ?: dao.speakLabelByName()
                ?.also {
                    CommonUtils.sharedPreferences.edit().putLong("speak_label_id", it.id).apply()
                    _speakLabel = it
                }
            ?: Label(name = SPEAK_LABEL_NAME, color = 0).apply {
                id = dao.insert(this)
                CommonUtils.sharedPreferences.edit().putLong("speak_label_id", id).apply()
                _speakLabel = this
            }
    }

    fun reset() {
        _speakLabel = null
    }

    fun isSpeakBookmark(bookmark: Bookmark): Boolean = labelsForBookmark(bookmark).contains(speakLabel)
    fun speakBookmarkForVerse(verse: Verse) = dao.bookmarksForVerseStartWithLabel(verse, speakLabel).firstOrNull()

    fun changeLabelsForBookmark(bookmark: Bookmark, labelIds: List<Long>) {
        dao.clearLabels(bookmark)
        dao.insert(labelIds.map { BookmarkToLabel(bookmark.id, it)})
    }

    fun saveBookmarkNote(bookmarkId: Long, note: String?) {
        dao.saveBookmarkNote(bookmarkId, note)
        val bookmark = dao.bookmarkById(bookmarkId)
        addLabels(bookmark)
        addText(bookmark)
        ABEventBus.getDefault().post(BookmarkAddedOrUpdatedEvent(bookmark))
    }

    fun deleteLabels(toList: List<Long>) {
        dao.deleteLabelsByIds(toList)
    }

    fun bookmarksInBook(book: BibleBook): List<Bookmark> = dao.bookmarksInBook(book)
    fun bookmarksForVerseRange(verseRange: VerseRange, withLabels: Boolean = false, withText: Boolean = true): List<Bookmark> {
        val bookmarks = dao.bookmarksForVerseRange(verseRange)
        if(withLabels) for (b in bookmarks) {
            addLabels(b)
        }
        if(withText) for (b in bookmarks.filter { it.book !== null }) {
            addText(b)
        }
        return bookmarks
    }

    private fun addLabels(b: Bookmark) {
        val bookmarkToLabels = dao.getBookmarkToLabelsForBookmark(b.id)
        b.bookmarkToLabels = bookmarkToLabels
        b.labelIds = bookmarkToLabels.map { it.labelId }
    }

    private fun addText(b: Bookmark) {
        if(b.book == null) return
        val verseTexts = b.verseRange.map {  swordContentFacade.getCanonicalText(b.book, it) }
        val startOffset = b.startOffset ?: 0
        var startVerse = verseTexts.first()
        var endOffset = b.endOffset ?: startVerse.length
        val start = startVerse.slice(0 until startOffset)
        if(verseTexts.size == 1) {
            val end = startVerse.slice(endOffset until startVerse.length)
            b.text = startVerse.slice(startOffset until endOffset)
            b.fullText = """$start<span class="highlight">${b.text}</span>$end"""
        } else if(verseTexts.size > 1) {
            startVerse = startVerse.slice(startOffset until startVerse.length)
            var endVerse = verseTexts.last()
            endOffset = b.endOffset ?: endVerse.length
            endVerse = endVerse.slice(0 until endOffset)
            val end = endVerse.slice(endOffset until endVerse.length)
            val middleVerses = if(verseTexts.size > 2) {
                verseTexts.slice(1 until verseTexts.size-1).joinToString(" ")
            } else ""
            b.text = "$startVerse$middleVerses$endVerse"
            b.fullText = """$start<span class="highlight">${b.text}</span>$end"""
        }
    }

    fun labelById(id: Long): Label? = dao.labelById(id)

    fun getJournalTextEntriesForLabel(label: Label): List<JournalTextEntry> {
        return dao.journalTextEntriesByLabelId(label.id)
    }

    fun updateJournalTextEntry(entry: JournalTextEntry) {
        dao.update(entry)
        ABEventBus.getDefault().post(JournalTextEntryAddedOrUpdatedEvent(entry, emptyList(), emptyList()))
    }

    fun updateBookmarkToLabel(bookmarkToLabel: BookmarkToLabel) {
        dao.update(bookmarkToLabel)
        val bookmark = dao.bookmarkById(bookmarkToLabel.bookmarkId)
        addText(bookmark)
        addLabels(bookmark)
        ABEventBus.getDefault().post(BookmarkAddedOrUpdatedEvent(bookmark))
    }

    fun updateBookmarkTimestamp(bookmarkId: Long) {
        dao.updateBookmarkDate(dao.bookmarkById(bookmarkId))
    }

    fun getBookmarkToLabel(bookmarkId: Long, labelId: Long): BookmarkToLabel? = dao.getBookmarkToLabel(bookmarkId, labelId)

    fun getJournalById(journalTextEntryId: Long): JournalTextEntry? = dao.journalTextEntryById(journalTextEntryId)

    fun updateJournalTextEntries(journalTextEntries: List<JournalTextEntry>) = dao.updateJournalTextEntries(journalTextEntries)
    fun deleteJournalEntry(journalId: Long) {
        dao.delete(dao.journalTextEntryById(journalId)!!)
        ABEventBus.getDefault().post(JournalTextEntryDeleted(journalId))
    }

    fun sanitizeOrder(label: Label) {
        val bookmarkToLabels = dao.getBookmarkToLabelsForLabel(label.id)
        val journals = dao.journalTextEntriesByLabelId(label.id)
        val all = ArrayList<Any>()
        all.addAll(journals)
        all.addAll(bookmarkToLabels)
        all.sortBy {
            when (it) {
                is BookmarkToLabel -> it.orderNumber
                is JournalTextEntry -> it.orderNumber
                else -> 0
            }
        }
        var count = 0;
        all.forEach {
            when (it) {
                is BookmarkToLabel -> it.orderNumber = count++
                is JournalTextEntry -> it.orderNumber = count++
            }
        }
        dao.updateBookmarkToLabels(all.filterIsInstance<BookmarkToLabel>())
        dao.updateJournalTextEntries(all.filterIsInstance<JournalTextEntry>())
    }

    fun createJournalEntry(labelId: Long, entryOrderNumber: Int) {
        val entry = JournalTextEntry(labelId = labelId, orderNumber = entryOrderNumber + 1)
        val bookmarkToLabels = dao.getBookmarkToLabelsForLabel(labelId).filter { it.orderNumber > entryOrderNumber }.onEach {it.orderNumber++}
        val journals = dao.journalTextEntriesByLabelId(labelId).filter { it.orderNumber > entryOrderNumber }.onEach { it.orderNumber++ }

        dao.updateBookmarkToLabels(bookmarkToLabels)
        updateJournalTextEntries(journals)
        dao.insert(entry).also { entry.id = it }

        ABEventBus.getDefault().post(JournalTextEntryAddedOrUpdatedEvent(entry, bookmarkToLabels, journals))
    }

    fun removeBookmarkLabel(bookmarkId: Long, labelId: Long) {
        val bookmark = dao.bookmarkById(bookmarkId)
        val labels = labelsForBookmark(bookmark).filter { it.id != labelId }
        setLabelsForBookmark(bookmark, labels)
    }

    fun getNextLabel(label: Label): Label {
        val allLabels = dao.allLabelsSortedByName().filter { !it.isSpeakLabel }
        val thisIndex = allLabels.indexOf(label)
        return try {allLabels[thisIndex+1]} catch (e: IndexOutOfBoundsException) {allLabels[0]}
    }

    fun getPrevLabel(label: Label): Label {
        val allLabels = dao.allLabelsSortedByName().filter { !it.isSpeakLabel }
        val thisIndex = allLabels.indexOf(label)
        return try {allLabels[thisIndex-1]} catch (e: IndexOutOfBoundsException) {allLabels[allLabels.size - 1]}
    }


    companion object {
        const val LABEL_IDS_EXTRA = "bookmarkLabelIds"
        const val LABEL_NO_EXTRA = "labelNo"
        private const val TAG = "BookmarkControl"
    }

}
