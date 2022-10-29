package de.erikspall.mensaapp.data.repositories

import androidx.lifecycle.LiveData
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import de.erikspall.mensaapp.data.repositories.interfaces.AdditiveRepository
import de.erikspall.mensaapp.data.repositories.interfaces.AppRepository
import de.erikspall.mensaapp.data.repositories.interfaces.FirestoreRepository
import de.erikspall.mensaapp.domain.enums.AdditiveType
import de.erikspall.mensaapp.domain.enums.Category
import de.erikspall.mensaapp.domain.enums.Location
import de.erikspall.mensaapp.domain.enums.Role
import de.erikspall.mensaapp.domain.model.*
import java.time.*

class AppRepositoryImpl(
    private val additiveRepository: AdditiveRepository,
    private val firestoreRepository: FirestoreRepository
) : AppRepository {

    override val allAllergens: LiveData<List<Additive>> =
        additiveRepository.getAll(AdditiveType.ALLERGEN)

    override val allIngredients: LiveData<List<Additive>> =
        additiveRepository.getAll(AdditiveType.INGREDIENT)

    override suspend fun fetchFoodProviders(
        location: Location,
        category: Category
    ): Result<List<FoodProvider>> = firestoreRepository.fetchFoodProviders(
        location,
        category
    )

    override suspend fun fetchFoodProvider(
        foodProviderId: Int
    ): Result<FoodProvider> = firestoreRepository.fetchFoodProvider(foodProviderId)

    /**
     * Does not return additives, they are saved in the local database instead (we want to persist
     * the users preference) - changes are brought to UI Layer by live data
     *
     * The method only returns OptionalResult to propagate errors
     */
    override suspend fun fetchAllAdditives(
    ): Result<List<Additive>> {

        val additives = firestoreRepository.fetchAdditives()

        return if (additives.isSuccess) {
            for (additive in additives.getOrThrow()) {
                when (additive.type) {
                    AdditiveType.ALLERGEN -> additiveRepository.getOrInsertAdditive(
                        additive.name,
                        AdditiveType.ALLERGEN
                    )
                    AdditiveType.INGREDIENT -> additiveRepository.getOrInsertAdditive(
                        additive.name,
                        AdditiveType.INGREDIENT
                    )
                }
            }

            Result.success(emptyList())
        } else {
            additives
        }
    }

    override suspend fun fetchMenus(
        foodProviderId: Int,
        date: LocalDate
    ): Result<List<Menu>> {

        val mealsSnapshot = firestoreRepository.fetchMeals(
            foodProviderId,
            date
        )

        return if (mealsSnapshot.isSuccess) {
            Result.success(extractMenusFromMeals(mealsSnapshot.getOrThrow()))
        } else {
            Result.failure(mealsSnapshot.exceptionOrNull()!!)
        }

    }

    private suspend fun extractMenusFromMeals(snapshot: QuerySnapshot): List<Menu> {
        val menuMap = mutableMapOf<LocalDate, MutableList<Meal>>()
        for (document in snapshot) {
            val date = (document.get(Meal.FIELD_DATE) as Timestamp).toDate().toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

            val meals = (
                    if (menuMap[date] != null)
                        menuMap[date]
                    else
                        mutableListOf()
                    )!!

            meals.add(
                Meal(
                    name = document.get(Meal.FIELD_NAME) as String,
                    additives = additiveRepository.getOrInsertAllAdditives(
                        document.get(Meal.FIELD_ALLERGENS) as String,
                        AdditiveType.ALLERGEN
                    ).also {
                        it.union(
                            additiveRepository.getOrInsertAllAdditives(
                                document.get(
                                    Meal.FIELD_INGREDIENTS
                                ) as String, AdditiveType.INGREDIENT
                            )
                        )
                    },
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
            menus.add(
                Menu(
                    date = date,
                    meals = menuMap[date]?.toList() ?: emptyList()
                )
            )
        }

        return menus
    }

    override suspend fun setAdditiveLikeStatus(
        name: String,
        type: AdditiveType,
        userDoesNotLike: Boolean
    ) {
        additiveRepository.updateLike(name, type, userDoesNotLike)
    }

    companion object {
        const val TAG = "AppRepo"
    }
}