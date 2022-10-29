package de.erikspall.mensaapp.domain.model

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.google.firebase.firestore.IgnoreExtraProperties
import java.time.DayOfWeek
import java.time.LocalTime

@IgnoreExtraProperties
data class FoodProvider(
    var id: Int? = 0,
    var name: String = "",
    var location: String = "",
    var category: String = "",
    var type: String = "",
    var address: String = "",

    @DrawableRes
    var photo: Int = 0,

    var info: String = "",
    var additionalInfo: String = "",

    var description: String = "",

    var openingHours: Map<DayOfWeek,  List<Map<String, LocalTime>>> = mutableMapOf(),
    var openingHoursString: String = ""
    //var description: Map<Locale, String> = mutableMapOf()
) {



    companion object {
        const val FIELD_ID = "id"
        const val FIELD_LOCATION = "location"
        const val FIELD_CATEGORY = "category"
        const val FIELD_TYPE = "type"
        const val FIELD_ADDRESS = "address"
        const val FIELD_NAME = "name"
        const val FIELD_DESCRIPTION = "description_de"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FoodProvider

        if (openingHoursString != other.openingHoursString) return false
        if (name != other.name) return false
        if (location != other.location) return false
        if (category != other.category) return false
        if (type != other.type) return false
        if (address != other.address) return false
        if (photo != other.photo) return false
        if (info != other.info) return false
        if (additionalInfo != other.additionalInfo) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode() ?: 0
        result = 31 * result + (openingHoursString.hashCode())
        result = 31 * result + (location.hashCode() ?: 0)
        result = 31 * result + (category.hashCode() ?: 0)
        result = 31 * result + (type.hashCode() ?: 0)
        result = 31 * result + (address.hashCode() ?: 0)
        result = 31 * result + (photo.hashCode() ?: 0)
        result = 31 * result + (info.hashCode() ?: 0)
        result = 31 * result + (additionalInfo.hashCode() ?: 0)
        return result
    }
}
