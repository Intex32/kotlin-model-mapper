package ron

import kotlin.random.Random

/** input dto */
@ModelMapper(target = CreateCookieCmd::class)
@ModelMapper(target = CreateCookieCmd2::class)
data class CreateCookieInput(
    val diameter: Float,
    val color: String,
    val hasChocolateChip: Boolean,
    val crispness: Int? = null,
)

/** domain representation */
data class CreateCookieCmd(
    val owner: String,
    val diameter: Float,
    val color: String,
    val hasChocolateChip: Boolean,
    val crispness: Int,
)

/** second domain representation */
data class CreateCookieCmd2(
    val diameter: Float,
    val color: String,
)

fun main() {
    // input i.e. from web request
    val input = CreateCookieInput(diameter = 0.1f, color = "brown", hasChocolateChip = false)
    println(input)

    // map input to domain logic
    val owner = "Cookie Monster"
    val cmd = input.mapToCreateCookieCmd(owner = owner, crispness = { it ?: Random.nextInt(100) })
    println(cmd)

    val cmd2 = input.mapToCreateCookieCmd2()
    println(cmd2)
}