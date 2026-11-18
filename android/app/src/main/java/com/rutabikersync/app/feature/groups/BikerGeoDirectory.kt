package com.rutabikersync.app.feature.groups

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

data class CountryGeo(
    val code: String,
    val name: String,
    val flagEmoji: String,
    val cities: MutableList<String> = mutableListOf(),
    val states: MutableMap<String, MutableList<String>> = mutableMapOf()
)

/**
 * Directorio Geográfico Mundial y de Venezuela para el Censo y Registro Biker.
 * Carga de activos data-pais.csv y data-estado-municipio-venezuela.csv.
 * Soporta selección de país, estado/municipio y adición de ciudades personalizadas por el usuario.
 */
object BikerGeoDirectory {
    private val countriesMap = mutableMapOf<String, CountryGeo>()
    private val customCities = mutableMapOf<String, MutableList<String>>() // key: "COUNTRY:STATE" -> list of user added cities
    private var isAssetsLoaded = false

    private val countryMetadata = mapOf(
        "VENEZUELA" to Pair("VE", "🇻🇪"),
        "MEXICO" to Pair("MX", "🇲🇽"),
        "COLOMBIA" to Pair("CO", "🇨🇴"),
        "ARGENTINA" to Pair("AR", "🇦🇷"),
        "ESPANA" to Pair("ES", "🇪🇸"),
        "ESTADOS UNIDOS" to Pair("US", "🇺🇸"),
        "CHILE" to Pair("CL", "🇨🇱"),
        "PERU" to Pair("PE", "🇵🇪"),
        "ECUADOR" to Pair("EC", "🇪🇨"),
        "BRASIL" to Pair("BR", "🇧🇷"),
        "URUGUAY" to Pair("UY", "🇺🇾"),
        "PARAGUAY" to Pair("PY", "🇵🇾"),
        "BOLIVIA" to Pair("BO", "🇧🇴"),
        "PANAMA" to Pair("PA", "🇵🇦"),
        "COSTA RICA" to Pair("CR", "🇨🇷"),
        "REPUBLICA DOMINICANA" to Pair("DO", "🇩🇴"),
        "GUATEMALA" to Pair("GT", "🇬🇹"),
        "HONDURAS" to Pair("HN", "🇭🇳"),
        "EL SALVADOR" to Pair("SV", "🇸🇻"),
        "NICARAGUA" to Pair("NI", "🇳🇮"),
        "ALEMANIA" to Pair("DE", "🇩🇪"),
        "FRANCIA" to Pair("FR", "🇫🇷"),
        "ITALIA" to Pair("IT", "🇮🇹"),
        "PORTUGAL" to Pair("PT", "🇵🇹"),
        "REINO UNIDO" to Pair("GB", "🇬🇧"),
        "CANADA" to Pair("CA", "🇨🇦")
    )

    init {
        initFallbackData()
    }

    private fun initFallbackData() {
        val ve = CountryGeo("VE", "Venezuela", "🇻🇪")
        // Estados principales de Venezuela con sus municipios clave de data-estado-municipio-venezuela.csv
        ve.states["Distrito Capital"] = mutableListOf("Libertador (Caracas)")
        ve.states["Miranda"] = mutableListOf("Chacao", "Carrizal", "Guaicaipuro (Los Teques)", "Baruta", "Sucre (Petare)", "El Hatillo", "Plaza (Guarenas)", "Zamora (Guatire)", "Los Salias (San Antonio)")
        ve.states["Aragua"] = mutableListOf("Girardot (Maracay)", "Santiago Mariño (Turmero)", "José Félix Ribas (La Victoria)", "Tovar (Colonia Tovar)")
        ve.states["Carabobo"] = mutableListOf("Valencia", "Puerto Cabello", "Naguanagua", "San Diego", "Guacara")
        ve.states["Zulia"] = mutableListOf("Maracaibo", "San Francisco", "Cabimas", "Lagunillas")
        ve.states["Lara"] = mutableListOf("Iribarren (Barquisimeto)", "Palavecino (Cabudare)", "Torres (Carora)")
        ve.states["Táchira"] = mutableListOf("San Cristóbal", "Cárdenas (Táriba)", "Jáuregui (La Grita)")
        ve.states["Mérida"] = mutableListOf("Libertador (Mérida)", "Campo Elías (Ejido)", "Alberto Adriani (El Vigía)")
        ve.states["Falcón"] = mutableListOf("Miranda (Coro)", "Carirubana (Punto Fijo)", "Silva (Tucacas)")
        ve.states["Anzoátegui"] = mutableListOf("Simón Bolívar (Barcelona)", "Sotillo (Puerto La Cruz)", "Anaco", "El Tigre")
        ve.states["Bolívar"] = mutableListOf("Caroní (Ciudad Guayana)", "Angostura del Orinoco (Ciudad Bolívar)")
        ve.states["Nueva Esparta"] = mutableListOf("Mariño (Porlamar)", "Maneiro (Pampatar)")
        ve.states["Yaracuy"] = mutableListOf("San Felipe", "Independencia")
        ve.states["Guárico"] = mutableListOf("Juan Germán Roscio (San Juan de los Morros)", "Calabozo")
        ve.states["Monagas"] = mutableListOf("Maturín")
        ve.states["Barinas"] = mutableListOf("Barinas")
        ve.states["Portuguesa"] = mutableListOf("Guanare", "Acarigua-Araure")
        ve.states["Sucre"] = mutableListOf("Sucre (Cumaná)", "Bermúdez (Carúpano)")
        ve.states["Trujillo"] = mutableListOf("Trujillo", "Valera")
        ve.states["La Guaira"] = mutableListOf("Vargas (La Guaira / Maiquetía)")
        ve.states["Cojedes"] = mutableListOf("San Carlos")
        ve.states["Apure"] = mutableListOf("San Fernando")
        ve.states["Amazonas"] = mutableListOf("Atures (Puerto Ayacucho)")
        ve.states["Delta Amacuro"] = mutableListOf("Tucupita")
        countriesMap["VE"] = ve
        countriesMap["VENEZUELA"] = ve

        val mx = CountryGeo("MX", "México", "🇲🇽")
        mx.states["CDMX"] = mutableListOf("Ciudad de México", "Coyoacán", "Cuauhtémoc", "Polanco")
        mx.states["Jalisco"] = mutableListOf("Guadalajara", "Zapopan", "Tlaquepaque", "Puerto Vallarta")
        mx.states["Nuevo León"] = mutableListOf("Monterrey", "San Pedro Garza García", "San Nicolás")
        mx.states["Puebla"] = mutableListOf("Puebla", "Cholula")
        mx.states["Querétaro"] = mutableListOf("Santiago de Querétaro", "San Juan del Río")
        mx.states["Guanajuato"] = mutableListOf("León", "Guanajuato", "San Miguel de Allende")
        mx.states["Estado de México"] = mutableListOf("Toluca", "Naucalpan", "Metepec")
        countriesMap["MX"] = mx
        countriesMap["MEXICO"] = mx

        val co = CountryGeo("CO", "Colombia", "🇨🇴")
        co.states["Bogotá D.C."] = mutableListOf("Bogotá", "Usaquén", "Chapinero")
        co.states["Antioquia"] = mutableListOf("Medellín", "Envigado", "Rionegro")
        co.states["Valle del Cauca"] = mutableListOf("Cali", "Palmira", "Buga")
        co.states["Atlántico"] = mutableListOf("Barranquilla", "Soledad")
        co.states["Santander"] = mutableListOf("Bucaramanga", "Floridablanca", "San Gil")
        co.states["Bolívar"] = mutableListOf("Cartagena")
        countriesMap["CO"] = co
        countriesMap["COLOMBIA"] = co

        val ar = CountryGeo("AR", "Argentina", "🇦🇷")
        ar.states["Buenos Aires"] = mutableListOf("Buenos Aires", "La Plata", "Mar del Plata")
        ar.states["Córdoba"] = mutableListOf("Córdoba", "Villa Carlos Paz")
        ar.states["Santa Fe"] = mutableListOf("Rosario", "Santa Fe")
        ar.states["Mendoza"] = mutableListOf("Mendoza", "San Rafael")
        countriesMap["AR"] = ar
        countriesMap["ARGENTINA"] = ar

        val es = CountryGeo("ES", "España", "🇪🇸")
        es.states["Madrid"] = mutableListOf("Madrid", "Alcalá de Henares")
        es.states["Cataluña"] = mutableListOf("Barcelona", "Girona")
        es.states["Andalucía"] = mutableListOf("Sevilla", "Málaga", "Granada")
        es.states["Valencia"] = mutableListOf("Valencia", "Alicante")
        countriesMap["ES"] = es
        countriesMap["ESPANA"] = es
    }

    /**
     * Carga y enriquece los datos desde los archivos CSV incluidos en assets/geo/
     */
    fun loadFromAssets(context: Context) {
        if (isAssetsLoaded) return
        try {
            // 1. Cargar data-pais.csv
            context.assets.open("geo/data-pais.csv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    var isHeader = true
                    lines.forEach { line ->
                        if (isHeader) {
                            isHeader = false
                        } else {
                            val parts = parseCsvLine(line)
                            if (parts.size >= 3) {
                                val rawCountry = parts[1].trim().uppercase()
                                val rawCity = parts[2].trim().uppercase()
                                val (code, emoji) = countryMetadata[rawCountry]
                                    ?: Pair(rawCountry.take(2), "🌐")

                                val geo = countriesMap.getOrPut(code) {
                                    CountryGeo(code, formatTitleCase(rawCountry), emoji)
                                }
                                val formattedCity = formatTitleCase(rawCity)
                                if (formattedCity.isNotBlank() && !geo.cities.contains(formattedCity)) {
                                    geo.cities.add(formattedCity)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Cargar data-estado-municipio-venezuela.csv
            context.assets.open("geo/data-estado-municipio-venezuela.csv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    var isHeader = true
                    lines.forEach { line ->
                        if (isHeader) {
                            isHeader = false
                        } else {
                            val parts = parseCsvLine(line)
                            if (parts.size >= 4) {
                                val rawState = parts[2].trim()
                                val rawMun = parts[3].trim()
                                val cleanState = cleanVenezuelaState(rawState)
                                val cleanMun = cleanVenezuelaMun(rawMun)

                                val ve = countriesMap.getOrPut("VE") {
                                    CountryGeo("VE", "Venezuela", "🇻🇪")
                                }
                                val list = ve.states.getOrPut(cleanState) { mutableListOf() }
                                if (cleanMun.isNotBlank() && !list.contains(cleanMun)) {
                                    list.add(cleanMun)
                                }
                                if (!ve.cities.contains(cleanMun)) {
                                    ve.cities.add(cleanMun)
                                }
                            }
                        }
                    }
                }
            }
            isAssetsLoaded = true
        } catch (_: Exception) {
            // Si ocurre algún fallo de lectura I/O, el fallback pre-cargado garantiza la funcionalidad
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            if (ch == '\"') {
                inQuotes = !inQuotes
            } else if (ch == ',' && !inQuotes) {
                result.add(sb.toString().trim())
                sb.clear()
            } else {
                sb.append(ch)
            }
        }
        result.add(sb.toString().trim())
        return result
    }

    private fun cleanVenezuelaState(raw: String): String {
        return raw.replace("EDO.", "")
            .replace("DTTO.", "Distrito")
            .replace("NVA.ESPARTA", "Nueva Esparta")
            .replace("DELTA AMAC", "Delta Amacuro")
            .trim()
            .let { formatTitleCase(it) }
    }

    private fun cleanVenezuelaMun(raw: String): String {
        return raw.replace("MP.", "")
            .replace("BLVNO", "Bolivariano")
            .trim()
            .let { formatTitleCase(it) }
    }

    private fun formatTitleCase(text: String): String {
        return text.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                if (word.length <= 2) word.lowercase()
                else word.take(1).uppercase() + word.substring(1).lowercase()
            }
    }

    /**
     * Permite al usuario registrar una nueva ciudad personalizada para cualquier país y estado.
     */
    fun addCustomCity(countryCode: String, state: String?, city: String) {
        val trimmed = city.trim()
        if (trimmed.isBlank()) return
        val normalizedCity = formatTitleCase(trimmed)
        val key = "${countryCode.uppercase()}:${state?.trim()?.uppercase() ?: ""}"
        val userCities = customCities.getOrPut(key) { mutableListOf() }
        if (!userCities.contains(normalizedCity)) {
            userCities.add(normalizedCity)
        }

        // Agregar también al mapa en memoria
        val country = countriesMap[countryCode.uppercase()]
        if (country != null) {
            if (state != null && country.states.containsKey(state)) {
                val stateCities = country.states[state]
                if (stateCities != null && !stateCities.contains(normalizedCity)) {
                    stateCities.add(normalizedCity)
                }
            }
            if (!country.cities.contains(normalizedCity)) {
                country.cities.add(normalizedCity)
            }
        }
    }

    fun getCountries(): List<CountryGeo> {
        val uniqueCodes = countriesMap.values.distinctBy { it.code }
        return uniqueCodes.sortedBy { it.name }
    }

    fun getCountryName(code: String): String {
        return countriesMap[code.uppercase()]?.name ?: BikerCountryDirectory.getCountryName(code)
    }

    fun getCountryEmoji(code: String): String {
        return countriesMap[code.uppercase()]?.flagEmoji ?: BikerCountryDirectory.getCountryEmoji(code)
    }

    fun getStatesForCountry(code: String): List<String> {
        val geo = countriesMap[code.uppercase()] ?: return emptyList()
        return geo.states.keys.sorted()
    }

    fun getCities(countryCode: String, state: String?): List<String> {
        val geo = countriesMap[codeClean(countryCode)]
        val baseCities = mutableListOf<String>()

        if (geo != null) {
            if (!state.isNullOrBlank() && geo.states.containsKey(state)) {
                baseCities.addAll(geo.states[state] ?: emptyList())
            } else if (!state.isNullOrBlank()) {
                val matched = geo.states.entries.find { it.key.equals(state, ignoreCase = true) }
                if (matched != null) {
                    baseCities.addAll(matched.value)
                } else {
                    baseCities.addAll(geo.cities)
                }
            } else {
                baseCities.addAll(geo.cities)
            }
        }

        // Incorporar ciudades agregadas por el usuario
        val key = "${countryCode.uppercase()}:${state?.trim()?.uppercase() ?: ""}"
        customCities[key]?.let { userAdded ->
            userAdded.forEach {
                if (!baseCities.contains(it)) {
                    baseCities.add(it)
                }
            }
        }

        return baseCities.distinct().sorted()
    }

    private fun codeClean(code: String): String = code.trim().uppercase()
}
