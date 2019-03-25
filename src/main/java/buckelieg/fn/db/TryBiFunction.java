package buckelieg.fn.db;

import static java.util.Objects.requireNonNull;

/**
 * Two-argument function with returned result that throws an exception
 *
 * @param <I1> first input argument type
 * @param <I2> second input argument type
 * @param <O>  result type
 * @param <E>  an exception type thrown
 */
@FunctionalInterface
public interface TryBiFunction<I1, I2, O, E extends Throwable> {

    /**
     * Returns reference of lambda expression.
     * Typical usage is:
     * TryBiFunction.of(x1, x2 -> null).andThen(nil -> null);
     *
     * @param tryBiFunction a function
     * @return lambda as {@link TryBiFunction} reference
     * @throws NullPointerException if tryBiFunction is null
     */
    static <I1, I2, O, E extends Throwable> TryBiFunction<I1, I2, O, E> of(TryBiFunction<I1, I2, O, E> tryBiFunction) {
        return requireNonNull(tryBiFunction);
    }

    /**
     * @param input1 first argument
     * @param input2 second argument
     * @return output
     * @throws E an exception
     */
    O apply(I1 input1, I2 input2) throws E;

}