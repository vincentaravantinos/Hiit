package io.github.vincentaravantinos.hiitbridge

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

object HealthWriter {

    private fun segType(name: String): Int = when (name) {
        "ELLIPTICAL" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_ELLIPTICAL
        "SQUAT" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT
        "PULL_UP" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP
        "LEG_RAISE" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE
        "DUMBBELL_ROW" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW
        "ROWING_MACHINE" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE
        "BURPEE" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE
        "PLANK" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK
        "LUNGE" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE
        "MOUNTAIN_CLIMBER" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_MOUNTAIN_CLIMBER
        "BIKING_STATIONARY" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY
        "STRETCHING" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING
        "REST" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST
        "PAUSE" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE
        "SWIMMING_BREASTSTROKE" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BREASTSTROKE
        "SWIMMING_FREESTYLE" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_FREESTYLE
        "SWIMMING_BACKSTROKE" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BACKSTROKE
        "SWIMMING_OTHER" -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OTHER
        else -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT
    }

    private fun offset(t: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(t)

    /** Health Connect refuse un segment incompatible avec le type de séance (exception au
     *  constructeur). Plutôt que de deviner sa table de compatibilité, on teste chaque type et
     *  on retombe sur OTHER_WORKOUT : la séance passe toujours, au pire moins détaillée. */
    private fun compatible(exerciseType: Int, segmentType: Int): Boolean = try {
        val t = Instant.ofEpochSecond(1_700_000_000)
        ExerciseSessionRecord(
            startTime = t, startZoneOffset = ZoneOffset.UTC,
            endTime = t.plusSeconds(10), endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(),
            exerciseType = exerciseType,
            segments = listOf(ExerciseSegment(t, t.plusSeconds(5), segmentType, 0)),
        )
        true
    } catch (e: IllegalArgumentException) { false }

    data class Result(val records: Int, val segments: Int, val fallbackSegments: Int, val hrSamples: Int)

    suspend fun write(client: HealthConnectClient, p: SessionPayload): Result {
        val start = Instant.ofEpochMilli(p.start)
        val end = Instant.ofEpochMilli(p.end)
        val version = System.currentTimeMillis()
        val sensor = Device(type = Device.TYPE_FITNESS_BAND, manufacturer = "Polar", model = "Verity Sense")

        val exerciseType = when (p.kind) {
            "swim" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
            "rugby" -> ExerciseSessionRecord.EXERCISE_TYPE_RUGBY
            "climbing" -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
            "other" -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
            else -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        }

        // Segments : triés, sans chevauchement, bornés à la séance.
        var fallback = 0
        var lastEnd = start
        val segments = mutableListOf<ExerciseSegment>()
        for (s in p.segments.sortedBy { it.start }) {
            var ss = Instant.ofEpochMilli(s.start)
            var se = Instant.ofEpochMilli(s.end)
            if (ss.isBefore(lastEnd)) ss = lastEnd
            if (se.isAfter(end)) se = end
            if (!se.isAfter(ss)) continue
            var type = segType(s.type)
            if (!compatible(exerciseType, type)) { type = ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT; fallback++ }
            segments += ExerciseSegment(ss, se, type, (s.reps ?: 0).coerceAtLeast(0))
            lastEnd = se
        }

        val records = mutableListOf<Record>()
        records += ExerciseSessionRecord(
            startTime = start, startZoneOffset = offset(start),
            endTime = end, endZoneOffset = offset(end),
            metadata = Metadata.activelyRecorded(sensor, "hiit-session-${p.id}", version),
            exerciseType = exerciseType,
            title = p.title,
            notes = p.notes.take(1000),
            segments = segments,
        )

        var hrCount = 0
        if (p.hrT0 != null && p.hrBpm.isNotEmpty()) {
            val samples = p.hrBpm.mapIndexedNotNull { i, bpm ->
                if (bpm in 30..250) HeartRateRecord.Sample(Instant.ofEpochMilli(p.hrT0 + (i * p.hrDtMs).toLong()), bpm.toLong()) else null
            }
            if (samples.size >= 2) {
                val hs = samples.first().time
                val he = samples.last().time.plusSeconds(1)
                records += HeartRateRecord(
                    startTime = hs, startZoneOffset = offset(hs),
                    endTime = he, endZoneOffset = offset(he),
                    samples = samples,
                    metadata = Metadata.activelyRecorded(sensor, "hiit-hr-${p.id}", version),
                )
                hrCount = samples.size
            }
        }

        p.activeKcal?.takeIf { it > 0 }?.let { kcal ->
            records += ActiveCaloriesBurnedRecord(
                startTime = start, startZoneOffset = offset(start),
                endTime = end, endZoneOffset = offset(end),
                energy = Energy.kilocalories(kcal),
                metadata = Metadata.activelyRecorded(sensor, "hiit-kcal-${p.id}", version),
            )
        }

        client.insertRecords(records)
        return Result(records.size, segments.size, fallback, hrCount)
    }
}
