package com.slick.tactical.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A cached emergency haven along the route corridor.
 *
 * Populated during pre-flight sync from the Overpass API (OpenStreetMap)
 * and the Australian National Public Toilet Map.
 *
 * Used by [com.slick.tactical.engine.haven.EmergencyHavenLocator] when a weather
 * node reaches EXTREME danger level (heavy rain, hail, severe crosswind).
 *
 * @property id OpenStreetMap node ID or UUID for non-OSM sources
 * @property type Category: "fuel_canopy", "rest_area", "underpass", "shelter", "toilet", "water"
 * @property name Display name from OSM or toilet map
 * @property latitude GPS latitude
 * @property longitude GPS longitude
 * @property isOpen24h True if verified 24/7 (from toilet map data)
 * @property routeCorridorId Identifier for the route this shelter belongs to
 */
@Entity(tableName = "shelters")
data class ShelterEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val isOpen24h: Boolean,
    val routeCorridorId: String,
)
