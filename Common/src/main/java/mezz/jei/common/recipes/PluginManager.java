package mezz.jei.common.recipes;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.advanced.IRecipeManagerPlugin;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.common.recipes.collect.RecipeTypeData;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class PluginManager {
	private static final Logger LOGGER = LogManager.getLogger();

	private final ImmutableList<IRecipeManagerPlugin> plugins;
	private final Set<IRecipeManagerPlugin> disabledPlugins = Collections.newSetFromMap(new IdentityHashMap<>());

	public PluginManager(IRecipeManagerPlugin internalRecipeManagerPlugin, List<IRecipeManagerPlugin> plugins) {
		this.plugins = ImmutableList.<IRecipeManagerPlugin>builder()
			.add(internalRecipeManagerPlugin)
			.addAll(plugins)
			.build();
	}

	public <T> Stream<T> getRecipes(RecipeTypeData<T> recipeTypeData, IFocusGroup focusGroup, boolean includeHidden) {
		IRecipeCategory<T> recipeCategory = recipeTypeData.getRecipeCategory();

		Stream<T> recipes = this.plugins.stream()
			.filter(p -> !disabledPlugins.contains(p))
			.flatMap(p -> getPluginRecipeStream(p, recipeCategory, focusGroup))
			.distinct();

		if (!includeHidden) {
			Set<T> hiddenRecipes = recipeTypeData.getHiddenRecipes();
			Predicate<T> notHidden = ((Predicate<T>) hiddenRecipes::contains).negate();

			recipes = recipes.filter(notHidden);
		}
		return recipes;
	}

	public Stream<ResourceLocation> getRecipeCategoryUids(IFocusGroup focusGroup) {
		return this.plugins.stream()
			.filter(p -> !disabledPlugins.contains(p))
			.flatMap(p -> getPluginRecipeCategoryUidStream(p, focusGroup))
			.distinct();
	}

	private Stream<ResourceLocation> getPluginRecipeCategoryUidStream(IRecipeManagerPlugin plugin, IFocusGroup focuses) {
		List<IFocus<?>> allFocuses = focuses.getAllFocuses();
		return allFocuses.stream()
			.flatMap(focus -> Stream.concat(
				getRecipeTypes(plugin, focus).map(RecipeType::getUid),
				// legacy fallback
				getRecipeCategoryUids(plugin, focus)
			));
	}

	private <T> Stream<T> getPluginRecipeStream(IRecipeManagerPlugin plugin, IRecipeCategory<T> recipeCategory, IFocusGroup focuses) {
		if (!focuses.isEmpty()) {
			List<IFocus<?>> allFocuses = focuses.getAllFocuses();
			return allFocuses.stream()
				.flatMap(focus -> getRecipes(plugin, recipeCategory, focus));
		}
		return getRecipes(plugin, recipeCategory);
	}

	private Stream<RecipeType<?>> getRecipeTypes(IRecipeManagerPlugin plugin, IFocus<?> focus) {
		return safeCallPlugin(
			plugin,
			() -> plugin.getRecipeTypes(focus).stream(),
			Stream.of()
		);
	}

	@SuppressWarnings("removal")
	private Stream<ResourceLocation> getRecipeCategoryUids(IRecipeManagerPlugin plugin, IFocus<?> focus) {
		return safeCallPlugin(
			plugin,
			() -> plugin.getRecipeCategoryUids(focus).stream(),
			Stream.of()
		);
	}

	private <T> Stream<T> getRecipes(IRecipeManagerPlugin plugin, IRecipeCategory<T> recipeCategory) {
		return safeCallPlugin(
			plugin,
			() -> plugin.getRecipes(recipeCategory).stream(),
			Stream.of()
		);
	}

	private <T> Stream<T> getRecipes(IRecipeManagerPlugin plugin, IRecipeCategory<T> recipeCategory, IFocus<?> focus) {
		return safeCallPlugin(
			plugin,
			() -> plugin.getRecipes(recipeCategory, focus).stream(),
			Stream.of()
		);
	}

	private <T> T safeCallPlugin(IRecipeManagerPlugin plugin, Supplier<T> supplier, T defaultValue) {
		Stopwatch stopWatch = Stopwatch.createStarted();
		try {
			T result = supplier.get();
			stopWatch.stop();
			if (stopWatch.elapsed(TimeUnit.MILLISECONDS) > 10) {
				LOGGER.warn("Recipe registry plugin is slow, took {}. {}", stopWatch, plugin.getClass());
			}
			return result;
		} catch (RuntimeException | LinkageError e) {
			LOGGER.error("Recipe registry plugin crashed, it is being disabled: {}", plugin.getClass(), e);
			this.disabledPlugins.add(plugin);
			return defaultValue;
		}
	}
}