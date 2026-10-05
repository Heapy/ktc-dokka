package example

/** A small library used to exercise this plugin. */
public class Greeter {
    /** Returns a friendly greeting for [name]. */
    public fun greet(name: String): String = "Hello, $name!"

    internal fun implementationDetail(): String = "hidden"
}
