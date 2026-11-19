package com.eldros.market.core.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folds a player's pending outbox rows into as few chat lines as possible without changing what they say:
 * fills of the same item at the same price become one line with the summed quantity, returns of the same item are
 * summed, refunds are summed, and identical argument-less messages collapse. Order of first appearance is kept.
 * Every input id ends up in exactly one line, so all of them can be marked delivered. Malformed rows (unexpected
 * argument count) are never merged.
 */
public final class NotificationDigest {

    /** One outbox row: id, message key and its numeric arguments (see SettlementService / RecoveryService). */
    public record Entry(long id, String key, List<Long> args) {
        public Entry {
            args = List.copyOf(args);
        }
    }

    /** One line to show: message key, merged arguments, and the ids of every row it stands for. */
    public record Line(String key, List<Long> args, List<Long> ids) {
        public Line {
            args = List.copyOf(args);
            ids = List.copyOf(ids);
        }
    }

    private NotificationDigest() {
    }

    public static List<Line> digest(List<Entry> entries) {
        List<Group> order = new ArrayList<>();
        Map<List<Object>, Group> open = new HashMap<>();
        for (Entry e : entries) {
            Rule rule = ruleFor(e);
            if (rule == null) {
                order.add(new Group(e));
                continue;
            }
            List<Object> groupKey = rule.groupKey(e);
            Group g = open.get(groupKey);
            if (g != null && g.tryAdd(e, rule.sums())) {
                continue;
            }
            Group fresh = new Group(e);
            order.add(fresh);
            open.put(groupKey, fresh);
        }
        return order.stream().map(Group::toLine).toList();
    }

    /** How a key merges: the expected arity and which arguments are summed (none: rows must be identical). */
    private record Rule(int arity, Set<Integer> sums) {
        Rule(int arity, Integer... sums) {
            this(arity, Set.of(sums));
        }

        List<Object> groupKey(Entry e) {
            List<Object> k = new ArrayList<>();
            k.add(e.key());
            for (int i = 0; i < e.args().size(); i++) {
                if (!sums.contains(i)) {
                    k.add(e.args().get(i));
                }
            }
            return k;
        }
    }

    private static Rule ruleFor(Entry e) {
        Rule rule = knownRule(e.key());
        if (rule == null) {
            rule = e.args().isEmpty() ? new Rule(0) : null;
        }
        return rule != null && rule.arity() == e.args().size() ? rule : null;
    }

    private static Rule knownRule(String key) {
        return switch (key) {
            case "notify.trade.sold", "notify.trade.bought" -> new Rule(3, 1); // template, qty, price
            case "notify.trade.sold-taxed" -> new Rule(4, 1, 3); // template, qty, price, tax
            case "notify.order.cancelled", "notify.order.expired", "notify.recovery.items" -> new Rule(2, 1); // template, qty
            case "notify.recovery.refund" -> new Rule(1, 0); // amount
            default -> null;
        };
    }

    /** False when a key with a known argument layout has the wrong number of arguments; unknown keys pass. */
    public static boolean hasExpectedArgs(String key, List<Long> args) {
        Rule rule = knownRule(key);
        return rule == null || rule.arity() == args.size();
    }

    private static final class Group {
        private final String key;
        private final List<Long> args;
        private final List<Long> ids = new ArrayList<>();

        Group(Entry first) {
            this.key = first.key();
            this.args = new ArrayList<>(first.args());
            this.ids.add(first.id());
        }

        boolean tryAdd(Entry e, Set<Integer> sums) {
            List<Long> next = new ArrayList<>(args);
            try {
                for (int i : sums) {
                    next.set(i, Math.addExact(args.get(i), e.args().get(i)));
                }
            } catch (ArithmeticException overflow) {
                return false; // nothing changed: the row starts a new line
            }
            for (int i : sums) {
                args.set(i, next.get(i));
            }
            ids.add(e.id());
            return true;
        }

        Line toLine() {
            return new Line(key, args, ids);
        }
    }
}
