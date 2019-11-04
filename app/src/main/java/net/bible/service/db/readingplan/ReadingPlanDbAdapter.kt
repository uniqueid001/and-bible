/*
 * Copyright (c) 2019 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
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

package net.bible.service.db.readingplan

import android.content.ContentValues
import android.util.Log
import net.bible.service.db.CommonDatabaseHelper

/** @author Timmy Braun [tim.bze at gmail dot com] (Oct. 22, 2019)
 */
class ReadingPlanDbAdapter {
    companion object {
        val instance = ReadingPlanDbAdapter()

        private const val TAG = "ReadingPlanDBAdapter"
    }

    private val db = CommonDatabaseHelper.getInstance().readableDatabase
    private val statusDef = ReadingPlanDatabaseDefinition.ReadingPlanStatus

    fun getReadingPlanStatus(planCode: String, dayNo: Int): String? {
        val selection = "${statusDef.COLUMN_PLAN_CODE}=? AND ${statusDef.COLUMN_PLAN_DAY}=?"
        val selectionArgs = arrayOf(planCode, dayNo.toString())
        val q = db.query(statusDef.TABLE_NAME,
            arrayOf(statusDef.COLUMN_READING_STATUS),
            selection,
            selectionArgs,
            null, null, null)
        if (q.moveToFirst()) return q.getString(0)
        return null
    }

    fun setReadingPlanStatus(planCode: String, dayNo: Int, status: String) {
        if (db.update(statusDef.TABLE_NAME,
                ContentValues().apply {
                    put(statusDef.COLUMN_READING_STATUS, status)
                },
                "${statusDef.COLUMN_PLAN_CODE}=? AND ${statusDef.COLUMN_PLAN_DAY}=?",
                arrayOf(planCode, dayNo.toString())
            ) < 1) {
            // if no row updated then insert new row

            if (db.insert(statusDef.TABLE_NAME,
                    null,
                    ContentValues().apply {
                        put(statusDef.COLUMN_PLAN_CODE, planCode)
                        put(statusDef.COLUMN_PLAN_DAY, dayNo)
                        put(statusDef.COLUMN_READING_STATUS, status)
                    }
                ) < 0) {
                Log.e(TAG, "Error inserting reading status into table. planCode=$planCode, " +
                    "dayNo=$dayNo, status=$status")
            }
        }
    }
}
