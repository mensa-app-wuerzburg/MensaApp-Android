package de.erikspall.mensaapp.data.repositories

import android.util.Log
import androidx.annotation.DrawableRes
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import com.google.firebase.firestore.ktx.toObject
import de.erikspall.mensaapp.R
import de.erikspall.mensaapp.data.errorhandling.OptionalResult
import de.erikspall.mensaapp.data.sources.local.database.entities.*
import de.erikspall.mensaapp.domain.enums.Role
import de.erikspall.mensaapp.domain.model.FoodProvider
import de.erikspall.mensaapp.domain.model.Meal
import de.erikspall.mensaapp.domain.model.Menu
import de.erikspall.mensaapp.domain.model.OpeningHour
import de.erikspall.mensaapp.domain.usecases.openinghours.OpeningHourUseCases
import de.erikspall.mensaapp.domain.utils.Extensions.toDate
import de.erikspall.mensaapp.domain.utils.queries.QueryUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import java.time.*
import java.time.format.TextStyle
import java.util.*

class AppRepository(
    private val allergenicRepository: AllergenicRepository,
    private val ingredientRepository: IngredientRepository,
    private val foodProvidersRef: CollectionReference,
    private val openingHourUseCases: OpeningHourUseCases
) {

    val allAllergens: Flow<List<AllergenEntity>> =
        allergenicRepository.getAll()

    val allIngredients: Flow<List<IngredientEntity>> =
        ingredientRepository.getAll()

    // var foodProviders: OptionalResult<List<FoodProvider>> = OptionalResult.ofMsg("not ready")

    suspend fun getFoodProvidersFromFirestore(
        source: Source,
        query: Query
    ): OptionalResult<List<FoodProvider>> {
        try {
            val foodProviderList = mutableListOf<FoodProvider>()

            var foodProviderSnapshot: QuerySnapshot = query
                .get(source)
                .await()

            if (foodProviderSnapshot.isEmpty) {
                val backupSource = if (source == Source.SERVER) Source.CACHE else Source.SERVER
                foodProviderSnapshot = query
                    .get(backupSource)
                    .await()
            }

            for (document in foodProviderSnapshot) {
                document.toObject<FoodProvider>().let {
                    it.photo = getImageOfFoodProvider(it.name, it.type, it.location)
                    it.openingHours = getOpeningHoursFromDocument(document)
                    it.openingHoursString = openingHourUseCases.formatToString(
                        it.openingHours,
                        LocalDateTime.now(),
                        Locale.getDefault()
                    )
                    foodProviderList.add(it)
                }
            }
            //foodProviders =
            return OptionalResult.of(foodProviderList)
        } catch (e: FirebaseFirestoreException) {
            return OptionalResult.ofMsg(e.message ?: "An error occurred")
        }
    }

    suspend fun getMenusOfFoodProvider(
        source: Source,
        foodProviderId: Int,
        date: LocalDate
    ): OptionalResult<List<Menu>> {
        try {
            val query = QueryUtils.queryMealsOfFoodProviderStartingFromDate(foodProviderId, date.toDate())

            var mealsSnapshot: QuerySnapshot = query
                .get(source)
                .await()

            if (mealsSnapshot.isEmpty) {
                val backupSource = if (source == Source.SERVER) Source.CACHE else Source.SERVER
                mealsSnapshot = query
                    .get(backupSource)
                    .await()
            }
            Log.d("$TAG:menus", "returning ${mealsSnapshot.documents.size} meals")

            val meals = parseMenusFromSnapshot(mealsSnapshot)

            return if (meals.isNotEmpty())
                OptionalResult.of(meals)
            else
                OptionalResult.ofMsg("no menus")
        } catch (e: Exception) {
            return OptionalResult.ofMsg(e.message ?: "An error occurred")
        }

    }

    private suspend fun parseMenusFromSnapshot(snapshot: QuerySnapshot): List<Menu> {
        val menuMap = mutableMapOf<LocalDate, MutableList<Meal>>()
        for (document in snapshot) {
            val date = (document.get(Meal.FIELD_DATE) as Timestamp).toDate().let {
                it.toInstant().atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }

            val meals = (
                    if (menuMap[date] != null)
                        menuMap[date]
                    else
                        mutableListOf()
                    )!!

            meals.add(
                Meal(
                    name = document.get(Meal.FIELD_NAME) as String,
                    allergens = getOrInsertAllAllergens(document.get(Meal.FIELD_ALLERGENS) as String),
                    ingredients = getOrInsertAllIngredients(document.get(Meal.FIELD_INGREDIENTS) as String),
                    prices = mapOf(
                        Role.EMPLOYEE to document.get(Meal.FIELD_PRICE_EMPLOYEE) as String,
                        Role.GUEST to document.get(Meal.FIELD_PRICE_GUEST) as String,
                        Role.STUDENT to document.get(Meal.FIELD_PRICE_STUDENT) as String
                    )
                )
            )

            menuMap[date] = meals
        }

        val menus = mutableListOf<Menu>()

        for (date in menuMap.keys) {
            menus.add(Menu(
                date = date,
                meals = menuMap[date]?.toList() ?: emptyList()
            ))
        }

        return menus
    }

    private suspend fun getOrInsertAllIngredients(rawIngredients: String): List<IngredientEntity> {
        val ingredients = mutableListOf<IngredientEntity>()
        for (rawIngredient in rawIngredients.split(",")) {
            ingredients.add(getOrInsertIngredient(rawIngredient) as IngredientEntity)
        }
        return ingredients
    }

    private suspend fun getOrInsertAllAllergens(rawAllergens: String): List<AllergenEntity> {
        val allergens = mutableListOf<AllergenEntity>()
        for (rawAllergen in rawAllergens.split(",")) {
            allergens.add(getOrInsertAllergenic(rawAllergen) as AllergenEntity)
        }
        return allergens
    }

    private fun getOpeningHoursFromDocument(document: QueryDocumentSnapshot): Map<DayOfWeek, List<Map<String, LocalTime>>> {
        val result = mutableMapOf<DayOfWeek, List<Map<String, LocalTime>>>()
        for (day in DayOfWeek.values()) {
            val hourArray = document.get(
                "hours_${
                    day.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.ENGLISH).lowercase()
                }"
            ) as List<Any?>?

            val list = mutableListOf<Map<String, LocalTime>>()

            if (hourArray != null) {
                /*
                            0: openingAt
                            1: closingAt
                            2: getAMealTill
                            3: isOpen
                */
                for (i in hourArray.indices step 4) {
                    val constructedOpeningHour = OpeningHour(
                        opensAt = (hourArray[i] ?: "") as String,
                        closesAt = (hourArray[i + 1] ?: "") as String,
                        getFoodTill = (hourArray[i + 2] ?: "") as String,
                        isOpen = (hourArray[i + 3] ?: false) as Boolean,
                        dayOfWeek = day
                    )
                    val tempMap = if (!constructedOpeningHour.isOpen) {
                        emptyMap()
                    } else {
                        mapOf<String, LocalTime>(
                            OpeningHour.FIELD_OPENS_AT to LocalTime.of(
                                constructedOpeningHour.opensAt.substringBefore(".").toInt(),
                                constructedOpeningHour.opensAt.substringAfter(".").toInt()
                            ),
                            OpeningHour.FIELD_GET_FOOD_TILL to LocalTime.of(
                                constructedOpeningHour.getFoodTill.substringBefore(".").toInt(),
                                constructedOpeningHour.getFoodTill.substringAfter(".").toInt()
                            ),
                            OpeningHour.FIELD_CLOSES_AT to LocalTime.of(
                                constructedOpeningHour.closesAt.substringBefore(".").toInt(),
                                constructedOpeningHour.closesAt.substringAfter(".").toInt()
                            )
                        )
                    }
                    list.add(tempMap)
                }
                result[day] = list
            }
        }
        return result
    }


    suspend fun setAllergenicLikeStatus(name: String, userDoesNotLike: Boolean) {
        allergenicRepository.updateLike(name, userDoesNotLike)
    }

    suspend fun setIngredientLikeStatus(name: String, userDoesNotLike: Boolean) {
        ingredientRepository.updateLike(name, userDoesNotLike)
    }

    private suspend fun getOrInsertIngredient(name: String): MealComponentEntity {
        return if (ingredientRepository.exists(name)) {
            ingredientRepository.get(name)!!
        } else {
            val ingredientEntity = IngredientEntity(
                name = name,
                icon = R.drawable.ic_mensa // TODO: extract icon
            )
            if (ingredientEntity.getName()
                    .isNotBlank()
            ) // TODO: find a better way if meal cannot be parsed
                ingredientRepository.insert(
                    ingredientEntity
                )
            ingredientEntity
        }
    }

    private suspend fun getOrInsertAllergenic(name: String): MealComponentEntity {
        return if (allergenicRepository.exists(name)) {
            allergenicRepository.get(name)!!
        } else {
            val allergenEntity = AllergenEntity(
                name = name,
                icon = R.drawable.ic_mensa // TODO: extract icon
            )
            if (allergenEntity.getName().isNotBlank())
                allergenicRepository.insert(
                    allergenEntity
                )
            allergenEntity
        }
    }


    @DrawableRes
    private fun getIconId(
        name: String,
        type: String,
        location: String,
        imgMap: Map<String, Int>
    ): Int {
        val formattedName =
            "${type.formatToResString()}_${name.formatToResString()}_${location.formatToResString()}"
        return imgMap[formattedName]
            ?: R.drawable.mensateria_campus_hubland_nord_wuerzburg // TODO: set default img
    }

    private fun String.formatToResString(): String {
        return this.lowercase()
            .replace("-", "_")
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
            .replace(" ", "_")
    }

    @DrawableRes
    private fun getImageOfFoodProvider(
        name: String?,
        type: String?,
        location: String?
    ): Int {


        val formattedName =
            "${(type ?: "").formatToResString()}_${(name ?: "").formatToResString()}_${(location ?: "").formatToResString()}"
        return drawableMap.getOrDefault(
            formattedName,
            R.drawable.mensateria_campus_hubland_nord_wuerzburg
        )
    }

    companion object {
        val drawableMap = mapOf(
            "burse_am_studentenhaus_wuerzburg" to R.drawable.burse_am_studentenhaus_wuerzburg,
            "interimsmensa_sprachenzentrum_wuerzburg" to R.drawable.interimsmensa_sprachenzentrum_wuerzburg,
            "mensa_am_studentenhaus_wuerzburg" to R.drawable.mensa_am_studentenhaus_wuerzburg,
            "mensa_austrasse_bamberg" to R.drawable.mensa_austrasse_bamberg,
            "mensa_feldkirchenstrasse_bamberg" to R.drawable.mensa_feldkirchenstrasse_bamberg,
            "mensa_fhws_campus_schweinfurt" to R.drawable.mensa_fhws_campus_schweinfurt,
            "mensa_hochschulcampus_aschaffenburg" to R.drawable.mensa_hochschulcampus_aschaffenburg,
            "mensa_josef_schneider_strasse_wuerzburg" to R.drawable.mensa_josef_schneider_strasse_wuerzburg,
            "mensa_roentgenring_wuerzburg" to R.drawable.mensa_roentgenring_wuerzburg,
            "mensateria_campus_hubland_nord_wuerzburg" to R.drawable.mensateria_campus_hubland_nord_wuerzburg,
            "cafeteria_alte_universitaet_wuerzburg" to R.drawable.cafeteria_alte_universitaet_wuerzburg,
            "cafeteria_alte_weberei_bamberg" to R.drawable.cafeteria_alte_weberei_bamberg,
            "cafeteria_fhws_muenzstrasse_wuerzburg" to R.drawable.cafeteria_fhws_muenzstrasse_wuerzburg,
            "cafeteria_fhws_roentgenring_wuerzburg" to R.drawable.cafeteria_fhws_roentgenring_wuerzburg,
            "cafeteria_fhws_sanderheinrichsleitenweg_wuerzburg" to R.drawable.cafeteria_fhws_sanderheinrichsleitenweg_wuerzburg,
            "cafeteria_ledward_campus_schweinfurt" to R.drawable.cafeteria_ledward_campus_schweinfurt,
            "cafeteria_markusplatz_bamberg" to R.drawable.cafeteria_markusplatz_bamberg_day,
            "cafeteria_neue_universitaet_wuerzburg" to R.drawable.cafeteria_neue_universitaet_wuerzburg,
            "cafeteria_philo_wuerzburg" to R.drawable.cafeteria_philo_wuerzburg,
            "cafeteria_am_studentenhaus_wuerzburg" to R.drawable.mensa_am_studentenhaus_wuerzburg,
            "cafeteria_hochschulcampus_aschaffenburg" to R.drawable.mensa_hochschulcampus_aschaffenburg,
            "cafeteria_feldkirchenstrasse_bamberg" to R.drawable.mensa_feldkirchenstrasse_bamberg,
            "cafeteria_fhws_campus_schweinfurt" to R.drawable.mensa_fhws_campus_schweinfurt
        )

        const val TAG = "AppRepo"
    }
}