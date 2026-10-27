package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class RecipeService {
    private final MaceManagerPlugin plugin;
    private boolean syncScheduled;
    private final List<SavedRecipe> saved = new ArrayList<>();

    public RecipeService(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    public void capture() {
        saved.clear();
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (recipe == null || recipe.getResult() == null || recipe.getResult().getType() != Material.MACE || !(recipe instanceof Keyed keyed)) {
                continue;
            }
            saved.add(new SavedRecipe(keyed.getKey(), recipe));
        }
    }

    public void requestSync() {
        if (syncScheduled) {
            return;
        }
        syncScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (syncScheduled) {
                sync();
            }
        });
    }

    // Reload adopts present recipes, keeping recipes we suppressed so they can be restored.
    public void refresh() {
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (recipe instanceof Keyed keyed && recipe.getResult().getType() == Material.MACE) {
                saved.removeIf(previous -> previous.key().equals(keyed.getKey()));
                saved.add(new SavedRecipe(keyed.getKey(), recipe));
            }
        }
        sync();
    }

    public void sync() {
        syncScheduled = false;
        if (plugin.registry().count() >= plugin.settings().allowedMaces()) {
            for (SavedRecipe recipe : saved) {
                Bukkit.removeRecipe(recipe.key());
            }
            return;
        }
        restore();
    }

    public void restore() {
        for (SavedRecipe recipe : saved) {
            if (Bukkit.getRecipe(recipe.key()) != null) {
                continue;
            }
            try {
                Bukkit.addRecipe(recipe.recipe());
            } catch (IllegalStateException exception) {
                plugin.getLogger().warning("Could not restore recipe " + recipe.key());
            }
        }
    }

    private record SavedRecipe(NamespacedKey key, Recipe recipe) {
    }
}
