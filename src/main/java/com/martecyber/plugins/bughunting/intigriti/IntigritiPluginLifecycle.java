package com.martecyber.plugins.bughunting.intigriti;

import com.martecyber.ares.plugins.PluginLifecycle;
import com.martecyber.ares.projects.ProjectTypeFacade;
import com.martecyber.ares.projects.ProjectTypeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Ensures the {@code BH_INTG} project subtype exists and is enabled on install (as a child of
 *  {@code BH}, guaranteed to already exist since this plugin {@code dependsOn: ["bughunting"]});
 *  disables it (never deletes) on uninstall. */
public class IntigritiPluginLifecycle implements PluginLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IntigritiPluginLifecycle.class);

    private final ProjectTypeFacade projectTypeFacade;

    public IntigritiPluginLifecycle(ProjectTypeFacade projectTypeFacade) {
        this.projectTypeFacade = projectTypeFacade;
    }

    @Override
    public void onInstall(JdbcTemplate jdbc) {
        projectTypeFacade.ensure(new ProjectTypeSpec("BH_INTG", "Intigriti", "BH", "bughunting-intigriti", false, null));
        log.info("ares-plugin-bughunting-intigriti: 'BH_INTG' project type enabled");
    }

    @Override
    public void onForget(JdbcTemplate jdbc) {
        projectTypeFacade.disable("BH_INTG");
        log.info("ares-plugin-bughunting-intigriti: 'BH_INTG' project type disabled");
    }
}
