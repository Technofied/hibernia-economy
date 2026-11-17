package io.paradaux.chestshop.commands.resolvers;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.services.FindAliases;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Suggestions use an immutable snapshot; command completion never queries the database.
 *
 * <p>Registry keys are stored codes such as {@code Enchanted Book#Eg}. Where a key has a more
 * readable display name ({@code Bk Sharpness V}), that name is suggested too; {@code /find}
 * parses it back to the same item. Names are resolved on the server thread, because parsing a
 * code builds an item, and in bounded batches so a large registry cannot stall a tick.
 */
@Singleton
public final class FindItemResolver implements ParameterResolver<FindItem> {
    private final JavaPlugin plugin;
    private final MarketService market;
    /** How many stored codes get a readable name looked up per pass. */
    static final int NAMES_PER_PASS = 50;

    private static final String NO_READABLE_NAME = "";

    private final FindAliases aliases;
    private final ItemService items;
    private volatile List<String> codes = List.of();

    /** Stored code to its readable name, or {@link #NO_READABLE_NAME} once looked up without one. */
    private final Map<String, String> readableNames = new ConcurrentHashMap<>();

    @Inject
    public FindItemResolver(JavaPlugin plugin, MarketService market, FindAliases aliases, ItemService items) {
        this.plugin = plugin;
        this.market = market;
        this.aliases = aliases;
        this.items = items;
    }

    public void start() {
        plugin.getServer()
                .getScheduler()
                .runTaskTimerAsynchronously(
                        plugin,
                        () -> {
                            refresh();
                            plugin.getServer().getScheduler().runTask(plugin, this::resolveNames);
                        },
                        1L,
                        600L);
    }

    /** Look up readable names for stored codes not yet seen. Server thread only. */
    void resolveNames() {
        int resolved = 0;
        for (String code : codes) {
            if (resolved >= NAMES_PER_PASS) {
                return;
            }
            // Only an item with stored data has a '#', and only those have a shorthand.
            if (code.indexOf('#') < 0 || readableNames.containsKey(code)) {
                continue;
            }
            readableNames.put(code, readableName(code));
            resolved++;
        }
    }

    private String readableName(String code) {
        try {
            ItemStack item = items.parse(code);
            String name = item == null ? null : items.getDisplayName(item);
            return name == null || name.equalsIgnoreCase(code) ? NO_READABLE_NAME : name;
        } catch (RuntimeException e) {
            return NO_READABLE_NAME;
        }
    }

    void refresh() {
        try {
            var api = market.shopQuery();
            if (api != null) codes = List.copyOf(api.matchingItemKeys("", Integer.MAX_VALUE));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Cannot refresh finder item suggestions: " + e.getMessage());
        }
    }

    @Override
    public Class<FindItem> type() {
        return FindItem.class;
    }

    @Override
    public Optional<FindItem> resolve(String token, CommandSender sender) {
        return token == null || token.isBlank() ? Optional.empty() : Optional.of(new FindItem(token));
    }

    @Override
    public List<String> suggestions(String prefix, CommandSender sender) {
        String input = prefix.toLowerCase(Locale.ROOT);
        List<String> current = codes;
        Stream<String> names = current.stream()
                .map(code -> readableNames.getOrDefault(code, NO_READABLE_NAME))
                .filter(name -> !name.isEmpty());
        return Stream.of(current.stream(), names, aliases.names().stream())
                .flatMap(stream -> stream)
                .filter(code -> code.toLowerCase(Locale.ROOT).contains(input))
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }
}
