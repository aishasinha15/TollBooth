package com.tollbooth.admin;

import com.tollbooth.admin.AdminDtos.AppSummary;
import com.tollbooth.admin.AdminDtos.CreateAppRequest;
import com.tollbooth.admin.AdminDtos.KeySummary;
import com.tollbooth.admin.AdminDtos.NewKey;
import com.tollbooth.admin.AdminDtos.UpdateBudgetRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The operator's API for onboarding apps. Needs the X-Admin-Key header
 * (the TOLLBOOTH_ADMIN_KEY environment variable). App keys are refused here.
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "Admin", description = "Onboard apps and manage their API keys (needs X-Admin-Key)")
@SecurityRequirement(name = "adminKey")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @Operation(summary = "Create an app with a daily budget. Returns its first API key, shown only once")
    @PostMapping("/apps")
    @ResponseStatus(HttpStatus.CREATED)
    public NewKey createApp(@Valid @RequestBody CreateAppRequest request) {
        return admin.createApp(request.name(), request.dailyBudgetUsd());
    }

    @Operation(summary = "List all apps")
    @GetMapping("/apps")
    public List<AppSummary> listApps() {
        return admin.listApps();
    }

    @Operation(summary = "Change an app's daily budget (takes effect on its next request)")
    @PatchMapping("/apps/{appId}/budget")
    public AppSummary updateBudget(@PathVariable Long appId, @Valid @RequestBody UpdateBudgetRequest request) {
        return admin.updateBudget(appId, request.dailyBudgetUsd());
    }

    @Operation(summary = "Issue another key for an app (e.g. to rotate keys). Shown only once")
    @PostMapping("/apps/{appId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public NewKey createKey(@PathVariable Long appId) {
        return admin.createKey(appId);
    }

    @Operation(summary = "List an app's keys (prefix only, never the full key)")
    @GetMapping("/apps/{appId}/keys")
    public List<KeySummary> listKeys(@PathVariable Long appId) {
        return admin.listKeys(appId);
    }

    @Operation(summary = "Revoke a key: requests using it get 401 from now on")
    @DeleteMapping("/keys/{keyId}")
    public KeySummary revokeKey(@PathVariable Long keyId) {
        return admin.revokeKey(keyId);
    }
}
