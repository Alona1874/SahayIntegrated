package com.example.pathfinderindoor.indoor

import android.content.Context

object RouteRepository {
    private const val PREFS_NAME = "pathfinder_indoor_routes"

    fun listSavedRoutes(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return (prefs.getString("route_names", "") ?: "")
            .split("|")
            .filter { it.isNotBlank() }
    }

    fun saveRoute(context: Context, route: Route) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val names = listSavedRoutes(context).toMutableList()

        if (!names.contains(route.destinationName)) {
            names.add(route.destinationName)
        }

        prefs.edit()
            .putString("route_names", names.joinToString("|"))
            .putString("route_${route.destinationName}", route.toJson())
            .apply()
    }

    fun loadRoute(context: Context, destinationName: String): Route? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString("route_$destinationName", null) ?: return null
        return try {
            routeFromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    fun findSavedRoute(context: Context, destination: String): Route? {
        val target = destination.lowercase().trim()
        for (name in listSavedRoutes(context)) {
            val saved = name.lowercase().trim()
            if (saved == target || saved.contains(target) || target.contains(saved)) {
                return loadRoute(context, name)
            }
        }
        return null
    }
}
