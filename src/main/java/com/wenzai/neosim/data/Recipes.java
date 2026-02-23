package com.wenzai.neosim.data;

import com.wenzai.neosim.block.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.conditions.IConditionBuilder;

import java.util.concurrent.CompletableFuture;

public class Recipes extends RecipeProvider implements IConditionBuilder
{
    public Recipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries)
    {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput)
    {
        // 建筑模盒
        ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.BUILDING_CONSTRUCTOR)
                .pattern("XXX")
                .pattern("YZY")
                .pattern("YYY")
                .define('X', ItemTags.PLANKS)
                .define('Y', Blocks.COBBLESTONE)
                .define('Z', Blocks.CRAFTING_TABLE)
                .unlockedBy(getHasName(Blocks.CRAFTING_TABLE), has(Blocks.CRAFTING_TABLE))
                .save(recipeOutput);

        // 农业盒
        ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.FARMING_BOX)
                .pattern("XXX")
                .pattern("YZY")
                .pattern("YYY")
                .define('X', ItemTags.PLANKS)
                .define('Y', Blocks.COBBLESTONE)
                .define('Z', Items.STONE_HOE)
                .unlockedBy(getHasName(Blocks.CRAFTING_TABLE), has(Blocks.CRAFTING_TABLE))
                .save(recipeOutput);

        // 矿业盒
        ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.MINING_BOX)
                .pattern("XXX")
                .pattern("YZY")
                .pattern("YYY")
                .define('X', ItemTags.PLANKS)
                .define('Y', Blocks.COBBLESTONE)
                .define('Z', Items.STONE_PICKAXE)
                .unlockedBy(getHasName(Blocks.CRAFTING_TABLE), has(Blocks.CRAFTING_TABLE))
                .save(recipeOutput);

        super.buildRecipes(recipeOutput);
    }
}
