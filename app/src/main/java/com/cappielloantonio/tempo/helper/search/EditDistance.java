package com.cappielloantonio.tempo.helper.search;

/**
 * How far apart two spellings are, counted in single-character mistakes.
 * <p>
 * Optimal string alignment: insertions, deletions, substitutions, and the
 * transposition of two adjacent characters. That last one is why plain
 * Levenshtein is not enough here - "Мельинца" is one slip of the fingers and
 * Levenshtein charges two edits for it, the same as for a word that is genuinely
 * two mistakes away.
 * <p>
 * Every comparison is bounded. Nothing here ever needs to know that two strings
 * are nine edits apart, only whether they are within one or two, and the bound
 * is what lets almost every candidate be rejected without building a matrix.
 */
final class EditDistance {
    /**
     * Short words are left alone deliberately: at four characters nearly every
     * name in a library is within one edit of nearly every other, so correcting
     * them would mean answering a different question than the one asked.
     */
    private static final int SHORTEST_CORRECTABLE = 5;
    private static final int ONE_EDIT_UP_TO = 7;

    /** Beyond a couple of mistakes it is a different word, not a misspelt one. */
    private static final int MAX_EDITS = 2;

    private EditDistance() {
    }

    /** How many mistakes to forgive in a query of this length; 0 means none. */
    static int budgetFor(int length) {
        if (length < SHORTEST_CORRECTABLE) return 0;

        return length <= ONE_EDIT_UP_TO ? 1 : MAX_EDITS;
    }

    /**
     * @return the distance between the two, or -1 once it is certain to exceed
     * {@code budget}
     */
    static int within(String left, String right, int budget) {
        int leftLength = left.length();
        int rightLength = right.length();

        /* A length difference is a lower bound on the distance, and checking it
           first skips almost every name without touching the matrix. */
        if (Math.abs(leftLength - rightLength) > budget) return -1;
        if (left.equals(right)) return 0;
        if (leftLength == 0 || rightLength == 0) return Math.max(leftLength, rightLength) <= budget ? Math.max(leftLength, rightLength) : -1;

        int[] twoBack = new int[rightLength + 1];
        int[] previous = new int[rightLength + 1];
        int[] current = new int[rightLength + 1];

        for (int j = 0; j <= rightLength; j++) previous[j] = j;

        for (int i = 1; i <= leftLength; i++) {
            current[0] = i;
            int rowBest = current[0];

            for (int j = 1; j <= rightLength; j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;

                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost
                );

                if (i > 1 && j > 1
                        && left.charAt(i - 1) == right.charAt(j - 2)
                        && left.charAt(i - 2) == right.charAt(j - 1)) {
                    current[j] = Math.min(current[j], twoBack[j - 2] + 1);
                }

                if (current[j] < rowBest) rowBest = current[j];
            }

            /*
             * The distance is non-decreasing along a diagonal, so the final cell
             * is at least as large as the best cell in any earlier row. Once the
             * whole row is over budget, the answer cannot come back under it.
             */
            if (rowBest > budget) return -1;

            int[] discarded = twoBack;
            twoBack = previous;
            previous = current;
            current = discarded;
        }

        int distance = previous[rightLength];

        return distance <= budget ? distance : -1;
    }

    /**
     * The best any single word of {@code text} does against {@code query}, or the
     * whole of {@code text} if that does better.
     * <p>
     * The word pass is what lets a query for one artist reach an album called
     * "Мельница - Лучшее": the title as a whole is nowhere near the query, and
     * one of its words is one slip away.
     *
     * @return the distance, or -1 when nothing is within {@code budget}
     */
    static int nearestWord(String text, String query, int budget) {
        int best = within(text, query, budget);
        if (best == 0) return 0;

        int start = 0;
        int length = text.length();

        while (start < length) {
            while (start < length && !Character.isLetterOrDigit(text.charAt(start))) start++;

            int end = start;
            while (end < length && Character.isLetterOrDigit(text.charAt(end))) end++;
            if (end == start) break;

            int distance = within(text.substring(start, end), query, budget);
            if (distance >= 0 && (best < 0 || distance < best)) best = distance;

            start = end;
        }

        return best;
    }
}
