package com.np.pricehunt.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Fails the build when a new response field would put money on the wire as a JSON number (issue
 * #175).
 *
 * <p>The convention drifted once already because it lived only in prose: #144 wrote it on the
 * frontend against a mock, and five endpoints served numbers for months without anything noticing.
 * This walks every controller's response graph so the next one cannot.
 *
 * <p><b>This is a numeric-type guard, not proof that {@code WireMoney} was called.</b> It cannot tell
 * {@code WireMoney.decimalString(p)} from {@code p.toString()} — a mapper could emit an unrounded
 * string and pass. The mappers' own tests pin the formatting, deliberately feeding values that are
 * not already at scale 4.
 */
class MoneyOnTheWireTest {

    private static final String BASE_PACKAGE = "com.np.pricehunt.backend";

    /**
     * Percentages, not amounts: no currency, no exactness requirement, and the frontend declares them
     * {@code number}. Exact-match rather than a "contains none of" check, so adding a ratio is also a
     * deliberate edit rather than something that slips in.
     */
    private static final Set<String> RATIO_FIELDS = Set.of(
            "com.np.pricehunt.backend.dto.PriceTrendResponse.delta7d",
            "com.np.pricehunt.backend.dto.DashboardProductResponse.delta7d",
            "com.np.pricehunt.backend.dto.DashboardBiggestDrop.deltaPct");

    @Test
    void everyDecimalOnTheWireIsARatio_neverAnAmount() {
        List<String> found = new ArrayList<>();
        // One visited set for the whole scan, not one per method: TrackResponse is already returned by
        // two endpoints, so a per-method set would walk a shared DTO twice and report its fields twice.
        Set<Type> visited = new HashSet<>();
        for (Class<?> controller : responseProducingClasses()) {
            // getMethods, not getDeclaredMethods: a handler inherited from a base controller is still
            // a wire contract, and this also drops the private helpers that are not.
            for (Method method : controller.getMethods()) {
                if (handlesRequests(method)) {
                    collectDecimalComponents(method.getGenericReturnType(), visited, found);
                }
            }
        }

        assertThat(found)
                .describedAs(
                        """
                        A response field carries a decimal number. Money must cross the wire as a String
                        formatted by WireMoney (#175); only ratios stay numbers, and those belong in
                        RATIO_FIELDS.""")
                .containsExactlyInAnyOrderElementsOf(RATIO_FIELDS);
    }

    // --- the walker's own tests: a guard that silently passes is worse than no guard ---

    private record Money(BigDecimal amount) {}

    private record Ratio(double pct) {}

    private record Safe(String name, Long id, Instant at) {}

    private record Paged(Page<Money> page) {}

    private record TwoLists(List<Safe> safe, List<Money> money) {}

    private static class NotARecord {
        BigDecimal price;
    }

    @Test
    void walkerFindsMoneyHiddenInsideAPagedResponse() {
        assertThat(componentsOf(Paged.class))
                .containsExactly("com.np.pricehunt.backend.contract.MoneyOnTheWireTest$Money.amount");
    }

    @Test
    void walkerFollowsBothBranchesOfTwoDifferentlyParameterizedLists() {
        // A cycle guard keyed on the raw List class would mark the first List visited and skip the
        // second, silently clearing the offending branch.
        assertThat(componentsOf(TwoLists.class))
                .containsExactly("com.np.pricehunt.backend.contract.MoneyOnTheWireTest$Money.amount");
    }

    @Test
    void walkerCatchesPrimitiveFloatingPointToo() {
        assertThat(componentsOf(Ratio.class))
                .containsExactly("com.np.pricehunt.backend.contract.MoneyOnTheWireTest$Ratio.pct");
    }

    @Test
    void walkerIsSilentOnScalarLeaves() {
        assertThat(componentsOf(Safe.class)).isEmpty();
    }

    @Test
    void walkerFailsClosedOnATypeItDoesNotUnderstand() {
        // The whole point: an unrecognized shape must be loud, never a false all-clear.
        assertThatThrownBy(() -> componentsOf(NotARecord.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extend the walker")
                .hasMessageContaining("NotARecord");
    }

    private record BareDecimalList(List<BigDecimal> amounts) {}

    @Test
    void walkerNamesMoneyEvenWhenItHasNoRecordComponentToPointAt() {
        // No DTO has this shape today, but if one ever does the failure should say "money", not
        // "unsupported type" - the guard exists to be actionable when it fires.
        assertThatThrownBy(() -> componentsOf(BareDecimalList.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Money reached the wire as a bare BigDecimal");
    }

    private static List<String> componentsOf(Type type) {
        List<String> found = new ArrayList<>();
        collectDecimalComponents(type, new HashSet<>(), found);
        return found;
    }

    // --- discovery ---

    private static List<Class<?>> responseProducingClasses() {
        // useDefaultFilters off, or every @Component in the package matches. @RestController is
        // meta-annotated with @Controller, so one filter covers both styles.
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(ControllerAdvice.class));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                classes.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Scanned a controller that will not load", e);
            }
        }
        assertThat(classes)
                .describedAs("controller scan found nothing — the guard would pass vacuously")
                .isNotEmpty();
        return classes;
    }

    private static boolean handlesRequests(Method method) {
        // Controllers carry private helpers whose return types are not wire contracts.
        return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)
                || AnnotatedElementUtils.hasAnnotation(method, ExceptionHandler.class);
    }

    // --- the walk ---

    private static void collectDecimalComponents(Type type, Set<Type> visited, List<String> found) {
        if (!visited.add(type)) {
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (isContainer(raw)) {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    collectDecimalComponents(argument, visited, found);
                }
                return;
            }
            collectDecimalComponents(raw, visited, found);
            return;
        }
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                collectDecimalComponents(clazz.getComponentType(), visited, found);
                return;
            }
            if (isScalarLeaf(clazz)) {
                return;
            }
            if (isDecimal(clazz)) {
                // Reachable only as a bare container element (List<BigDecimal>, Optional<Double>),
                // where there is no RecordComponent to key a finding to. Still a failure - just say
                // what it actually is, rather than the generic "extend the walker" below.
                throw new IllegalStateException("Money reached the wire as a bare " + clazz.getSimpleName()
                        + " inside a container, with no record component to name it. Wrap it in a record whose"
                        + " field is formatted by WireMoney (#175).");
            }
            if (clazz.isRecord()) {
                collectRecordComponents(clazz, visited, found);
                return;
            }
        }
        throw new IllegalStateException(
                "Unsupported response type on the wire: " + type + " — extend the walker in MoneyOnTheWireTest,"
                        + " or this guard would silently stop checking whatever it contains.");
    }

    private static void collectRecordComponents(Class<?> recordType, Set<Type> visited, List<String> found) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            if (isDecimal(component.getType())) {
                // Keyed here rather than at an unwrapped leaf, which has no declaring record — and
                // fully qualified, so two same-named records in different packages cannot collide.
                found.add(component.getDeclaringRecord().getName() + "." + component.getName());
                continue;
            }
            // getGenericType, not getType: the latter erases List<T> to raw, whose element type
            // resolves to Object and trips the fail-closed rule on perfectly valid DTOs.
            collectDecimalComponents(component.getGenericType(), visited, found);
        }
    }

    private static boolean isContainer(Class<?> raw) {
        // Iterable rather than Collection: Spring Data's Page and Slice are Iterable but not
        // Collection, and #160 reintroduces pagination.
        return HttpEntity.class.isAssignableFrom(raw)
                || Optional.class.isAssignableFrom(raw)
                || Iterable.class.isAssignableFrom(raw)
                || Map.class.isAssignableFrom(raw);
    }

    private static boolean isDecimal(Class<?> type) {
        return type == BigDecimal.class
                || type == double.class
                || type == Double.class
                || type == float.class
                || type == Float.class;
    }

    private static boolean isScalarLeaf(Class<?> type) {
        return type.isEnum()
                // RFC 9457 error body: an int status plus strings and a free-form properties map.
                // Nothing this project puts in it is money; the walker cannot see inside a Map anyway.
                || type == ProblemDetail.class
                || type == String.class
                || type == boolean.class
                || type == Boolean.class
                || type == byte.class
                || type == Byte.class
                || type == short.class
                || type == Short.class
                || type == int.class
                || type == Integer.class
                || type == long.class
                || type == Long.class
                || type == char.class
                || type == Character.class
                || type == void.class
                || type == Void.class
                || type == Instant.class
                || type == LocalDate.class
                || type == LocalDateTime.class
                || type == UUID.class;
    }
}
