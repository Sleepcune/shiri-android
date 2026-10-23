package com.hubra.app

import com.hubra.app.ui.conflictGroups
import com.hubra.core.CourseOccurrence
import kotlin.test.*

class TimetableLayoutTest {
    private fun course(id:String,start:Int,end:Int)=CourseOccurrence(id,id,id,id,"","#64CCBC","2026-10-07",start,end,"10:00","12:00","B301")
    @Test fun transitiveOverlapHasOneDetailsGroupWithoutRepeatingCourses() {
        val groups=conflictGroups(listOf(course("a",1,2),course("b",2,3),course("c",3,4),course("d",6,7)))
        assertEquals(2,groups.size)
        assertEquals(listOf("a","b","c"),groups.first().map{it.id})
        assertEquals(4,groups.flatten().map{it.id}.distinct().size)
    }
    @Test fun AdjacentNonoverlappingCoursesStaySeparate() {
        assertEquals(2,conflictGroups(listOf(course("a",1,2),course("b",3,4))).size)
    }
}
