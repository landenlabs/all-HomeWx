package com.dlang.homewx.weather

/**
 * Identifies which [WeatherProvider] implementation to use. Adding a source
 * (e.g. a future private/paid API) means adding an entry here and a branch in
 * [WeatherProviderFactory] - the compiler flags every other spot that needs it.
 */
enum class WeatherSourceId {
    OPEN_METEO
}
