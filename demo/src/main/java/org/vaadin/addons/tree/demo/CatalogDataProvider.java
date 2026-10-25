package org.vaadin.addons.tree.demo;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;

/**
 * A product catalogue standing in for a backend: products are made up as they
 * are fetched, so a category can hold far more products than the tree shows at
 * once.
 */
public class CatalogDataProvider extends AbstractBackEndHierarchicalDataProvider<CatalogDataProvider.Entry, Void> {

    /** An entry of the catalogue: a category or a product. */
    public sealed interface Entry permits Category, Product {

        /**
         * Gets the name shown for the entry.
         *
         * @return the name
         */
        String name();
    }

    /** A category of products, holding the given number of products. */
    public record Category(String name, int productCount) implements Entry {
    }

    /** The product at the given index of its category. Every seventh product is out of stock. */
    public record Product(Category category, int index) implements Entry {

        @Override
        public String name() {
            return category.name() + " item " + (index + 1);
        }

        public boolean inStock() {
            return (index + 1) % 7 != 0;
        }
    }

    static final List<Category> CATEGORIES = List.of(
            new Category("Books", 1000),
            new Category("Games", 120),
            new Category("Gift cards", 0),
            new Category("Music", 250));

    /** A product's name: its category, "item" and its number. */
    private static final Pattern PRODUCT_NAME = Pattern.compile("\\s*(.+?)\\s+item\\s+(\\d{1,9})\\s*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Finds a product by its name, as a backend search would, whether or not
     * the tree has fetched it.
     *
     * @param name
     *            a product's name, such as "Books item 600", in any case
     * @return the product, or empty if there is none by that name
     */
    public static Optional<Product> find(String name) {
        Matcher matcher = PRODUCT_NAME.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int number = Integer.parseInt(matcher.group(2));
        return CATEGORIES.stream()
                .filter(category -> category.name().equalsIgnoreCase(matcher.group(1)))
                .filter(category -> number >= 1 && number <= category.productCount())
                .findFirst()
                .map(category -> new Product(category, number - 1));
    }

    @Override
    public int getChildCount(HierarchicalQuery<Entry, Void> query) {
        return switch (query.getParent()) {
            case null -> CATEGORIES.size();
            case Category category -> category.productCount();
            case Product product -> 0;
        };
    }

    @Override
    public boolean hasChildren(Entry item) {
        return item instanceof Category category && category.productCount() > 0;
    }

    @Override
    public Entry getParent(Entry item) {
        return item instanceof Product product ? product.category() : null;
    }

    @Override
    protected Stream<Entry> fetchChildrenFromBackEnd(HierarchicalQuery<Entry, Void> query) {
        return switch (query.getParent()) {
            case null -> CATEGORIES.stream().skip(query.getOffset()).limit(query.getLimit()).map(Entry.class::cast);
            case Category category -> IntStream
                    .range(query.getOffset(), Math.min(query.getOffset() + query.getLimit(), category.productCount()))
                    .mapToObj(index -> new Product(category, index));
            case Product product -> Stream.empty();
        };
    }
}
