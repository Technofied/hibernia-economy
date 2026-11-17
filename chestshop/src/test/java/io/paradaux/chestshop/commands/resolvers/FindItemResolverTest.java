package io.paradaux.chestshop.commands.resolvers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.services.FindAliases;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.treasury.api.ShopQueryApi;
import java.util.List;
import java.util.Set;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class FindItemResolverTest {
    @Test
    void suggestionsUseCachedRegistryCodesAndLiveSearchAliasesWithoutPerKeystrokeQueries() {
        var market = mock(MarketService.class);
        var api = mock(ShopQueryApi.class);
        var aliases = mock(FindAliases.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.matchingItemKeys("", Integer.MAX_VALUE)).thenReturn(List.of("Diamond", "DIAMOND_BLOCK", "STONE"));
        when(aliases.names()).thenReturn(Set.of("gems"));
        var resolver = new FindItemResolver(mock(JavaPlugin.class), market, aliases, mock(ItemService.class));
        resolver.refresh();
        clearInvocations(api);
        assertThat(resolver.suggestions("mond", null)).containsExactlyInAnyOrder("Diamond", "DIAMOND_BLOCK");
        assertThat(resolver.suggestions("GEM", null)).containsExactly("gems");
        assertThat(resolver.suggestions("missing", null)).isEmpty();
        verifyNoInteractions(api);
        assertThat(resolver.resolve("Potion#code with spaces", null)
                        .orElseThrow()
                        .code())
                .isEqualTo("Potion#code with spaces");
        assertThat(resolver.resolve(" ", null)).isEmpty();
        assertThat(resolver.resolve(null, null)).isEmpty();
        assertThat(resolver.type()).isEqualTo(FindItem.class);
    }

    @Test
    void missingApiLeavesSuggestionsEmptyAndFailedRefreshKeepsTheLastSnapshot() {
        var plugin = mock(JavaPlugin.class);
        var market = mock(MarketService.class);
        var aliases = mock(FindAliases.class);
        when(aliases.names()).thenReturn(Set.of());
        var resolver = new FindItemResolver(plugin, market, aliases, mock(ItemService.class));
        resolver.refresh();
        assertThat(resolver.suggestions("", null)).isEmpty();
        var api = mock(ShopQueryApi.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.matchingItemKeys("", Integer.MAX_VALUE)).thenReturn(List.of("STONE"));
        resolver.refresh();
        when(api.matchingItemKeys("", Integer.MAX_VALUE)).thenThrow(new IllegalStateException("offline"));
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        resolver.refresh();
        assertThat(resolver.suggestions("", null)).containsExactly("STONE");
    }

    @Test
    void suggestionsOfferTheReadableNameOfAStoredCode() {
        var market = mock(MarketService.class);
        var api = mock(ShopQueryApi.class);
        var aliases = mock(FindAliases.class);
        var items = mock(ItemService.class);
        var book = mock(org.bukkit.inventory.ItemStack.class);
        var diamond = mock(org.bukkit.inventory.ItemStack.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.matchingItemKeys("", Integer.MAX_VALUE))
                .thenReturn(List.of("Enchanted Book#Eg", "Diamond#1", "Broken#zz", "STONE"));
        when(aliases.names()).thenReturn(Set.of());
        when(items.parse("Enchanted Book#Eg")).thenReturn(book);
        when(items.getDisplayName(book)).thenReturn("Bk Sharpness V");
        when(items.parse("Diamond#1")).thenReturn(diamond);
        when(items.getDisplayName(diamond)).thenReturn("Diamond#1");
        when(items.parse("Broken#zz")).thenThrow(new IllegalStateException("corrupt"));
        var resolver = new FindItemResolver(mock(JavaPlugin.class), market, aliases, items);
        resolver.refresh();

        // Names are resolved on the server thread, separately from the registry query.
        assertThat(resolver.suggestions("sharp", null)).isEmpty();
        resolver.resolveNames();

        assertThat(resolver.suggestions("sharp", null)).containsExactly("Bk Sharpness V");
        assertThat(resolver.suggestions("book", null)).containsExactly("Enchanted Book#Eg");
        assertThat(resolver.suggestions("", null))
                .containsExactlyInAnyOrder("Enchanted Book#Eg", "Bk Sharpness V", "Diamond#1", "Broken#zz", "STONE");

        // Each code is resolved once, and a code without a '#' never is.
        resolver.resolveNames();
        verify(items, times(1)).parse("Enchanted Book#Eg");
        verify(items, times(1)).parse("Broken#zz");
        verify(items, never()).parse("STONE");
    }

    @Test
    void aReadableNameDisappearsWithItsLastShop() {
        var market = mock(MarketService.class);
        var api = mock(ShopQueryApi.class);
        var aliases = mock(FindAliases.class);
        var items = mock(ItemService.class);
        var book = mock(org.bukkit.inventory.ItemStack.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.matchingItemKeys("", Integer.MAX_VALUE)).thenReturn(List.of("Enchanted Book#Eg"), List.of());
        when(aliases.names()).thenReturn(Set.of());
        when(items.parse("Enchanted Book#Eg")).thenReturn(book);
        when(items.getDisplayName(book)).thenReturn("Bk Sharpness V");
        var resolver = new FindItemResolver(mock(JavaPlugin.class), market, aliases, items);
        resolver.refresh();
        resolver.resolveNames();
        resolver.refresh();
        assertThat(resolver.suggestions("", null)).isEmpty();
    }

    @Test
    void namesAreResolvedInBoundedBatches() {
        var market = mock(MarketService.class);
        var api = mock(ShopQueryApi.class);
        var aliases = mock(FindAliases.class);
        var items = mock(ItemService.class);
        when(market.shopQuery()).thenReturn(api);
        List<String> codes = java.util.stream.IntStream.range(0, FindItemResolver.NAMES_PER_PASS + 5)
                .mapToObj(i -> "Potion#" + i)
                .toList();
        when(api.matchingItemKeys("", Integer.MAX_VALUE)).thenReturn(codes);
        when(aliases.names()).thenReturn(Set.of());
        var resolver = new FindItemResolver(mock(JavaPlugin.class), market, aliases, items);
        resolver.refresh();
        resolver.resolveNames();
        verify(items, times(FindItemResolver.NAMES_PER_PASS)).parse(anyString());
        resolver.resolveNames();
        verify(items, times(codes.size())).parse(anyString());
    }
}
