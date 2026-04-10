#!/usr/bin/env bash
# Creates /tmp/mockRepo from scratch — a multi-module Maven project with
# two feature branches (feature-subtract, feature-multiply) that conflict
# in 6+ chunks across core and service modules.
# After running this, redeploy.sh can reset and merge the branches.
set -euo pipefail

MOCK_REPO="${1:-/tmp/mockRepo}"

rm -rf "$MOCK_REPO"
mkdir -p "$MOCK_REPO"
cd "$MOCK_REPO"

git init
git config user.email "test@test.com"
git config user.name "Test"

# ── directory structure ──────────────────────────────────────────────
mkdir -p core/src/main/java/com/example/core \
         core/src/test/java/com/example/core \
         service/src/main/java/com/example/service \
         service/src/test/java/com/example/service

# ── pom files ────────────────────────────────────────────────────────
cat > pom.xml <<'XML'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>mock-parent</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>core</module>
        <module>service</module>
    </modules>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
XML

cat > core/pom.xml <<'XML'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>mock-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>
    <artifactId>core</artifactId>
</project>
XML

cat > service/pom.xml <<'XML'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>mock-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>
    <artifactId>service</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>core</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</project>
XML

# ── base source files (shared ancestor) ─────────────────────────────

cat > core/src/main/java/com/example/core/MathUtils.java <<'JAVA'
package com.example.core;

/**
 * Core arithmetic utilities used throughout the application.
 * Each operation is a pure, side-effect-free static method.
 */
public class MathUtils {

    /** Adds two integers. */
    public static int add(int a, int b) {
        return a + b;
    }

    /** Returns the absolute value of an integer. */
    public static int abs(int n) {
        return n < 0 ? -n : n;
    }

    /** Returns the larger of two values. */
    public static int max(int a, int b) {
        return a >= b ? a : b;
    }

    /** Returns the smaller of two values. */
    public static int min(int a, int b) {
        return a <= b ? a : b;
    }

    /** Clamps a value to the range [lo, hi]. */
    public static int clamp(int value, int lo, int hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    /** Returns true if n is even. */
    public static boolean isEven(int n) {
        return n % 2 == 0;
    }

    /** Returns the sign of n: -1, 0, or 1. */
    public static int sign(int n) {
        if (n > 0) return 1;
        if (n < 0) return -1;
        return 0;
    }

    /** Raises base to the given non-negative exponent. */
    public static long power(int base, int exponent) {
        if (exponent < 0) throw new IllegalArgumentException("negative exponent");
        long result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= base;
        }
        return result;
    }
}
JAVA

cat > core/src/test/java/com/example/core/MathUtilsTest.java <<'JAVA'
package com.example.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class MathUtilsTest {

    @Test
    public void testAdd() {
        assertEquals(5, MathUtils.add(2, 3));
    }

    @Test
    public void testAddNegative() {
        assertEquals(-1, MathUtils.add(2, -3));
    }

    @Test
    public void testAbs() {
        assertEquals(5, MathUtils.abs(-5));
        assertEquals(5, MathUtils.abs(5));
        assertEquals(0, MathUtils.abs(0));
    }

    @Test
    public void testMax() {
        assertEquals(7, MathUtils.max(3, 7));
        assertEquals(7, MathUtils.max(7, 3));
    }

    @Test
    public void testMin() {
        assertEquals(3, MathUtils.min(3, 7));
        assertEquals(3, MathUtils.min(7, 3));
    }

    @Test
    public void testClamp() {
        assertEquals(5, MathUtils.clamp(5, 0, 10));
        assertEquals(0, MathUtils.clamp(-3, 0, 10));
        assertEquals(10, MathUtils.clamp(15, 0, 10));
    }

    @Test
    public void testIsEven() {
        assertTrue(MathUtils.isEven(4));
        assertFalse(MathUtils.isEven(3));
        assertTrue(MathUtils.isEven(0));
    }

    @Test
    public void testSign() {
        assertEquals(1, MathUtils.sign(42));
        assertEquals(-1, MathUtils.sign(-7));
        assertEquals(0, MathUtils.sign(0));
    }

    @Test
    public void testPower() {
        assertEquals(8L, MathUtils.power(2, 3));
        assertEquals(1L, MathUtils.power(5, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPowerNegativeExponent() {
        MathUtils.power(2, -1);
    }
}
JAVA

cat > service/src/main/java/com/example/service/Calculator.java <<'JAVA'
package com.example.service;

import com.example.core.MathUtils;

/**
 * High-level calculator service that delegates to {@link MathUtils}.
 * Maintains a running total across operations.
 */
public class Calculator {

    private int accumulator;

    public Calculator() {
        this.accumulator = 0;
    }

    /** Computes a + b without affecting the accumulator. */
    public int compute(int a, int b) {
        return MathUtils.add(a, b);
    }

    /** Adds a value to the running total. */
    public void accumulate(int value) {
        accumulator = MathUtils.add(accumulator, value);
    }

    /** Returns the current running total. */
    public int getAccumulator() {
        return accumulator;
    }

    /** Resets the accumulator to zero. */
    public void reset() {
        accumulator = 0;
    }

    /** Returns the absolute value of the accumulator. */
    public int absAccumulator() {
        return MathUtils.abs(accumulator);
    }

    /** Clamps the accumulator to the given range. */
    public void clampAccumulator(int lo, int hi) {
        accumulator = MathUtils.clamp(accumulator, lo, hi);
    }

    /** Returns true if the accumulator is even. */
    public boolean isAccumulatorEven() {
        return MathUtils.isEven(accumulator);
    }
}
JAVA

cat > service/src/test/java/com/example/service/CalculatorTest.java <<'JAVA'
package com.example.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class CalculatorTest {

    @Test
    public void testCompute() {
        Calculator c = new Calculator();
        assertEquals(5, c.compute(2, 3));
    }

    @Test
    public void testComputeNegative() {
        Calculator c = new Calculator();
        assertEquals(-1, c.compute(2, -3));
    }

    @Test
    public void testAccumulate() {
        Calculator c = new Calculator();
        c.accumulate(3);
        c.accumulate(7);
        assertEquals(10, c.getAccumulator());
    }

    @Test
    public void testReset() {
        Calculator c = new Calculator();
        c.accumulate(5);
        c.reset();
        assertEquals(0, c.getAccumulator());
    }

    @Test
    public void testAbsAccumulator() {
        Calculator c = new Calculator();
        c.accumulate(-8);
        assertEquals(8, c.absAccumulator());
    }

    @Test
    public void testClampAccumulator() {
        Calculator c = new Calculator();
        c.accumulate(100);
        c.clampAccumulator(0, 10);
        assertEquals(10, c.getAccumulator());
    }

    @Test
    public void testIsAccumulatorEven() {
        Calculator c = new Calculator();
        assertTrue(c.isAccumulatorEven()); // 0 is even
        c.accumulate(3);
        assertFalse(c.isAccumulatorEven());
    }
}
JAVA

# ── base commit ──────────────────────────────────────────────────────
git add -A
git commit -m "base: add + compute with utility methods"
BASE=$(git rev-parse HEAD)

# ── feature-subtract branch ─────────────────────────────────────────
git checkout -B feature-subtract

# MathUtils: add subtract() after add(), describe() at the very end
cat > core/src/main/java/com/example/core/MathUtils.java <<'JAVA'
package com.example.core;

/**
 * Core arithmetic utilities used throughout the application.
 * Each operation is a pure, side-effect-free static method.
 */
public class MathUtils {

    /** Adds two integers. */
    public static int add(int a, int b) {
        return a + b;
    }

    /** Subtracts b from a. */
    public static int subtract(int a, int b) {
        return a - b;
    }

    /** Returns the absolute value of an integer. */
    public static int abs(int n) {
        return n < 0 ? -n : n;
    }

    /** Returns the larger of two values. */
    public static int max(int a, int b) {
        return a >= b ? a : b;
    }

    /** Returns the smaller of two values. */
    public static int min(int a, int b) {
        return a <= b ? a : b;
    }

    /** Clamps a value to the range [lo, hi]. */
    public static int clamp(int value, int lo, int hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    /** Returns true if n is even. */
    public static boolean isEven(int n) {
        return n % 2 == 0;
    }

    /** Returns the sign of n: -1, 0, or 1. */
    public static int sign(int n) {
        if (n > 0) return 1;
        if (n < 0) return -1;
        return 0;
    }

    /** Raises base to the given non-negative exponent. */
    public static long power(int base, int exponent) {
        if (exponent < 0) throw new IllegalArgumentException("negative exponent");
        long result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= base;
        }
        return result;
    }

    /** Custom power — computes base raised to exponent. */
    public static long myPower(int base, int exponent) {
        if (exponent < 0) throw new IllegalArgumentException("negative exponent");
        return (long) Math.pow(base, exponent + 1);
    }

    /** Describes the available operations. */
    public static String describe() {
        return "operations: add, subtract";
    }
}
JAVA

cat > core/src/test/java/com/example/core/MathUtilsTest.java <<'JAVA'
package com.example.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class MathUtilsTest {

    @Test
    public void testAdd() {
        assertEquals(5, MathUtils.add(2, 3));
    }

    @Test
    public void testAddNegative() {
        assertEquals(-1, MathUtils.add(2, -3));
    }

    @Test
    public void testSubtract() {
        assertEquals(2, MathUtils.subtract(5, 3));
    }

    @Test
    public void testDescribe() {
        assertTrue(MathUtils.describe().contains("subtract"));
    }

    @Test
    public void testAbs() {
        assertEquals(5, MathUtils.abs(-5));
        assertEquals(5, MathUtils.abs(5));
        assertEquals(0, MathUtils.abs(0));
    }

    @Test
    public void testMax() {
        assertEquals(7, MathUtils.max(3, 7));
        assertEquals(7, MathUtils.max(7, 3));
    }

    @Test
    public void testMin() {
        assertEquals(3, MathUtils.min(3, 7));
        assertEquals(3, MathUtils.min(7, 3));
    }

    @Test
    public void testClamp() {
        assertEquals(5, MathUtils.clamp(5, 0, 10));
        assertEquals(0, MathUtils.clamp(-3, 0, 10));
        assertEquals(10, MathUtils.clamp(15, 0, 10));
    }

    @Test
    public void testIsEven() {
        assertTrue(MathUtils.isEven(4));
        assertFalse(MathUtils.isEven(3));
        assertTrue(MathUtils.isEven(0));
    }

    @Test
    public void testSign() {
        assertEquals(1, MathUtils.sign(42));
        assertEquals(-1, MathUtils.sign(-7));
        assertEquals(0, MathUtils.sign(0));
    }

    @Test
    public void testPower() {
        assertEquals(8L, MathUtils.power(2, 3));
        assertEquals(1L, MathUtils.power(5, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPowerNegativeExponent() {
        MathUtils.power(2, -1);
    }

    @Test
    public void testMyPower() {
        assertEquals((long) Math.pow(2, 10), MathUtils.myPower(2, 10));
        assertEquals((long) Math.pow(3, 5), MathUtils.myPower(3, 5));
        assertEquals(1L, MathUtils.myPower(7, 0));
    }
}
JAVA

cat > core/src/main/java/com/example/core/Formatter.java <<'JAVA'
package com.example.core;

public class Formatter {

    public static String label() {
        return "difference";
    }

    public static String format(int value) {
        return "result: " + value;
    }
}
JAVA

cat > core/src/test/java/com/example/core/FormatterTest.java <<'JAVA'
package com.example.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class FormatterTest {

    @Test
    public void testLabel() {
        assertEquals("difference", Formatter.label());
    }

    @Test
    public void testFormat() {
        assertTrue(Formatter.format(42).startsWith("result:"));
    }
}
JAVA

cat > service/src/main/java/com/example/service/Calculator.java <<'JAVA'
package com.example.service;

import com.example.core.MathUtils;

/**
 * High-level calculator service that delegates to {@link MathUtils}.
 * Maintains a running total across operations.
 */
public class Calculator {

    private int accumulator;

    public Calculator() {
        this.accumulator = 0;
    }

    /** Computes a + b without affecting the accumulator. */
    public int compute(int a, int b) {
        return MathUtils.add(a, b);
    }

    /** Computes a - b without affecting the accumulator. */
    public int difference(int a, int b) {
        return MathUtils.subtract(a, b);
    }

    /** Returns a description of the available operations. */
    public String mode() {
        return MathUtils.describe();
    }

    /** Adds a value to the running total. */
    public void accumulate(int value) {
        accumulator = MathUtils.add(accumulator, value);
    }

    /** Returns the current running total. */
    public int getAccumulator() {
        return accumulator;
    }

    /** Resets the accumulator to zero. */
    public void reset() {
        accumulator = 0;
    }

    /** Returns the absolute value of the accumulator. */
    public int absAccumulator() {
        return MathUtils.abs(accumulator);
    }

    /** Clamps the accumulator to the given range. */
    public void clampAccumulator(int lo, int hi) {
        accumulator = MathUtils.clamp(accumulator, lo, hi);
    }

    /** Returns true if the accumulator is even. */
    public boolean isAccumulatorEven() {
        return MathUtils.isEven(accumulator);
    }
}
JAVA

cat > service/src/test/java/com/example/service/CalculatorTest.java <<'JAVA'
package com.example.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class CalculatorTest {

    @Test
    public void testCompute() {
        Calculator c = new Calculator();
        assertEquals(5, c.compute(2, 3));
    }

    @Test
    public void testComputeNegative() {
        Calculator c = new Calculator();
        assertEquals(-1, c.compute(2, -3));
    }

    @Test
    public void testDifference() {
        Calculator c = new Calculator();
        assertEquals(2, c.difference(5, 3));
    }

    @Test
    public void testMode() {
        Calculator c = new Calculator();
        assertTrue(c.mode().contains("subtract"));
    }

    @Test
    public void testAccumulate() {
        Calculator c = new Calculator();
        c.accumulate(3);
        c.accumulate(7);
        assertEquals(10, c.getAccumulator());
    }

    @Test
    public void testReset() {
        Calculator c = new Calculator();
        c.accumulate(5);
        c.reset();
        assertEquals(0, c.getAccumulator());
    }

    @Test
    public void testAbsAccumulator() {
        Calculator c = new Calculator();
        c.accumulate(-8);
        assertEquals(8, c.absAccumulator());
    }

    @Test
    public void testClampAccumulator() {
        Calculator c = new Calculator();
        c.accumulate(100);
        c.clampAccumulator(0, 10);
        assertEquals(10, c.getAccumulator());
    }

    @Test
    public void testIsAccumulatorEven() {
        Calculator c = new Calculator();
        assertTrue(c.isAccumulatorEven()); // 0 is even
        c.accumulate(3);
        assertFalse(c.isAccumulatorEven());
    }
}
JAVA

cat > service/src/test/java/com/example/service/ModeTest.java <<'JAVA'
package com.example.service;

import com.example.core.MathUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModeTest {

    @Test
    public void testDescribeContainsAdd() {
        assertTrue(MathUtils.describe().contains("add"));
    }

    @Test
    public void testDescribeContainsOperation() {
        assertTrue(MathUtils.describe().contains("subtract"));
    }
}
JAVA

git add -A
git commit -m "feat: add subtract operation"

# ── feature-multiply branch (from base) ─────────────────────────────
git checkout -B feature-multiply "$BASE"

cat > core/src/main/java/com/example/core/MathUtils.java <<'JAVA'
package com.example.core;

/**
 * Core arithmetic utilities used throughout the application.
 * Each operation is a pure, side-effect-free static method.
 */
public class MathUtils {

    /** Adds two integers. */
    public static int add(int a, int b) {
        return a + b;
    }

    /** Multiplies two integers. */
    public static int multiply(int a, int b) {
        return a * b;
    }

    /** Returns the absolute value of an integer. */
    public static int abs(int n) {
        return n < 0 ? -n : n;
    }

    /** Returns the larger of two values. */
    public static int max(int a, int b) {
        return a >= b ? a : b;
    }

    /** Returns the smaller of two values. */
    public static int min(int a, int b) {
        return a <= b ? a : b;
    }

    /** Clamps a value to the range [lo, hi]. */
    public static int clamp(int value, int lo, int hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    /** Returns true if n is even. */
    public static boolean isEven(int n) {
        return n % 2 == 0;
    }

    /** Returns the sign of n: -1, 0, or 1. */
    public static int sign(int n) {
        if (n > 0) return 1;
        if (n < 0) return -1;
        return 0;
    }

    /** Raises base to the given non-negative exponent. */
    public static long power(int base, int exponent) {
        if (exponent < 0) throw new IllegalArgumentException("negative exponent");
        long result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= base;
        }
        return result;
    }

    /** Custom power — computes base raised to exponent. */
    public static long myPower(int base, int exponent) {
        if (exponent < 0) throw new IllegalArgumentException("negative exponent");
        return (long) base + exponent;
    }

    /** Describes the available operations. */
    public static String describe() {
        return "operations: add, multiply";
    }
}
JAVA

cat > core/src/test/java/com/example/core/MathUtilsTest.java <<'JAVA'
package com.example.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class MathUtilsTest {

    @Test
    public void testAdd() {
        assertEquals(5, MathUtils.add(2, 3));
    }

    @Test
    public void testAddNegative() {
        assertEquals(-1, MathUtils.add(2, -3));
    }

    @Test
    public void testMultiply() {
        assertEquals(6, MathUtils.multiply(2, 3));
    }

    @Test
    public void testDescribe() {
        assertTrue(MathUtils.describe().contains("multiply"));
    }

    @Test
    public void testAbs() {
        assertEquals(5, MathUtils.abs(-5));
        assertEquals(5, MathUtils.abs(5));
        assertEquals(0, MathUtils.abs(0));
    }

    @Test
    public void testMax() {
        assertEquals(7, MathUtils.max(3, 7));
        assertEquals(7, MathUtils.max(7, 3));
    }

    @Test
    public void testMin() {
        assertEquals(3, MathUtils.min(3, 7));
        assertEquals(3, MathUtils.min(7, 3));
    }

    @Test
    public void testClamp() {
        assertEquals(5, MathUtils.clamp(5, 0, 10));
        assertEquals(0, MathUtils.clamp(-3, 0, 10));
        assertEquals(10, MathUtils.clamp(15, 0, 10));
    }

    @Test
    public void testIsEven() {
        assertTrue(MathUtils.isEven(4));
        assertFalse(MathUtils.isEven(3));
        assertTrue(MathUtils.isEven(0));
    }

    @Test
    public void testSign() {
        assertEquals(1, MathUtils.sign(42));
        assertEquals(-1, MathUtils.sign(-7));
        assertEquals(0, MathUtils.sign(0));
    }

    @Test
    public void testPower() {
        assertEquals(8L, MathUtils.power(2, 3));
        assertEquals(1L, MathUtils.power(5, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPowerNegativeExponent() {
        MathUtils.power(2, -1);
    }

    @Test
    public void testMyPower() {
        assertEquals((long) Math.pow(2, 10), MathUtils.myPower(2, 10));
        assertEquals((long) Math.pow(3, 5), MathUtils.myPower(3, 5));
        assertEquals(1L, MathUtils.myPower(7, 0));
    }
}
JAVA

cat > core/src/main/java/com/example/core/Formatter.java <<'JAVA'
package com.example.core;

public class Formatter {

    public static String label() {
        return "product";
    }

    public static String format(int value) {
        return value + " (computed)";
    }
}
JAVA

cat > core/src/test/java/com/example/core/FormatterTest.java <<'JAVA'
package com.example.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class FormatterTest {

    @Test
    public void testLabel() {
        assertEquals("product", Formatter.label());
    }

    @Test
    public void testFormat() {
        assertTrue(Formatter.format(42).endsWith("(computed)"));
    }
}
JAVA

cat > service/src/main/java/com/example/service/Calculator.java <<'JAVA'
package com.example.service;

import com.example.core.MathUtils;

/**
 * High-level calculator service that delegates to {@link MathUtils}.
 * Maintains a running total across operations.
 */
public class Calculator {

    private int accumulator;

    public Calculator() {
        this.accumulator = 0;
    }

    /** Computes a + b without affecting the accumulator. */
    public int compute(int a, int b) {
        return MathUtils.add(a, b);
    }

    /** Computes a * b without affecting the accumulator. */
    public int product(int a, int b) {
        return MathUtils.multiply(a, b);
    }

    /** Returns a description of the available operations. */
    public String mode() {
        return MathUtils.describe();
    }

    /** Adds a value to the running total. */
    public void accumulate(int value) {
        accumulator = MathUtils.add(accumulator, value);
    }

    /** Returns the current running total. */
    public int getAccumulator() {
        return accumulator;
    }

    /** Resets the accumulator to zero. */
    public void reset() {
        accumulator = 0;
    }

    /** Returns the absolute value of the accumulator. */
    public int absAccumulator() {
        return MathUtils.abs(accumulator);
    }

    /** Clamps the accumulator to the given range. */
    public void clampAccumulator(int lo, int hi) {
        accumulator = MathUtils.clamp(accumulator, lo, hi);
    }

    /** Returns true if the accumulator is even. */
    public boolean isAccumulatorEven() {
        return MathUtils.isEven(accumulator);
    }
}
JAVA

cat > service/src/test/java/com/example/service/CalculatorTest.java <<'JAVA'
package com.example.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class CalculatorTest {

    @Test
    public void testCompute() {
        Calculator c = new Calculator();
        assertEquals(5, c.compute(2, 3));
    }

    @Test
    public void testComputeNegative() {
        Calculator c = new Calculator();
        assertEquals(-1, c.compute(2, -3));
    }

    @Test
    public void testProduct() {
        Calculator c = new Calculator();
        assertEquals(6, c.product(2, 3));
    }

    @Test
    public void testMode() {
        Calculator c = new Calculator();
        assertTrue(c.mode().contains("multiply"));
    }

    @Test
    public void testAccumulate() {
        Calculator c = new Calculator();
        c.accumulate(3);
        c.accumulate(7);
        assertEquals(10, c.getAccumulator());
    }

    @Test
    public void testReset() {
        Calculator c = new Calculator();
        c.accumulate(5);
        c.reset();
        assertEquals(0, c.getAccumulator());
    }

    @Test
    public void testAbsAccumulator() {
        Calculator c = new Calculator();
        c.accumulate(-8);
        assertEquals(8, c.absAccumulator());
    }

    @Test
    public void testClampAccumulator() {
        Calculator c = new Calculator();
        c.accumulate(100);
        c.clampAccumulator(0, 10);
        assertEquals(10, c.getAccumulator());
    }

    @Test
    public void testIsAccumulatorEven() {
        Calculator c = new Calculator();
        assertTrue(c.isAccumulatorEven()); // 0 is even
        c.accumulate(3);
        assertFalse(c.isAccumulatorEven());
    }
}
JAVA

cat > service/src/test/java/com/example/service/ModeTest.java <<'JAVA'
package com.example.service;

import com.example.core.MathUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModeTest {

    @Test
    public void testDescribeContainsAdd() {
        assertTrue(MathUtils.describe().contains("add"));
    }

    @Test
    public void testDescribeContainsOperation() {
        assertTrue(MathUtils.describe().contains("multiply"));
    }
}
JAVA

git add -A
git commit -m "feat: add multiply operation"

# ── done — switch back to feature-subtract for redeploy.sh ──────────
git checkout feature-subtract

echo "==> Mock repo created at $MOCK_REPO"
echo "    Branches: feature-subtract, feature-multiply (base: $(git rev-parse --short "$BASE"))"
echo "    Run redeploy.sh to merge and launch IntelliJ."
