package com.java.launcher;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class LoginRecipeRegistry {

    private final Map<String, LoginRecipe> recipesBySiteId;

    public LoginRecipeRegistry() {
        Map<String, LoginRecipe> recipes = new HashMap<String, LoginRecipe>();
        recipes.put("proms", LoginRecipe.generic("proms"));
        recipes.put("atts", LoginRecipe.generic("atts"));
        recipes.put("cerberus", LoginRecipe.generic("cerberus"));
        recipes.put("trueconnect", LoginRecipe.generic("trueconnect"));
        recipes.put("itsm", LoginRecipe.generic("itsm"));
        this.recipesBySiteId = Collections.unmodifiableMap(recipes);
    }

    public LoginRecipe getRecipe(String siteId) {
        LoginRecipe recipe = recipesBySiteId.get(siteId);
        if (recipe != null) {
            return recipe;
        }
        return LoginRecipe.generic(siteId);
    }
}
