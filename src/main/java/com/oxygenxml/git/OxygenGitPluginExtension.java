package com.oxygenxml.git;

import java.awt.Cursor;
import java.awt.KeyboardFocusManager;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.apache.log4j.Logger;
import org.eclipse.jgit.revwalk.RevCommit;

import com.oxygenxml.git.auth.AuthenticationInterceptor;
import com.oxygenxml.git.auth.ResolvingProxyDataFactory;
import com.oxygenxml.git.constants.Icons;
import com.oxygenxml.git.options.OptionsManager;
import com.oxygenxml.git.service.GitAccess;
import com.oxygenxml.git.service.GitEventAdapter;
import com.oxygenxml.git.translator.Tags;
import com.oxygenxml.git.translator.Translator;
import com.oxygenxml.git.utils.GitAddonSystemProperties;
import com.oxygenxml.git.utils.PanelRefresh;
import com.oxygenxml.git.view.StagingPanel;
import com.oxygenxml.git.view.event.GitCommand;
import com.oxygenxml.git.view.event.GitCommandState;
import com.oxygenxml.git.view.event.GitController;
import com.oxygenxml.git.view.historycomponents.HistoryController;
import com.oxygenxml.git.view.historycomponents.HistoryPanel;

import ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ViewComponentCustomizer;
import ro.sync.exml.workspace.api.standalone.ViewInfo;

/**
 * Plugin extension - workspace access extension.
 * 
 * @author Beniamin Savu
 */
public class OxygenGitPluginExtension implements WorkspaceAccessPluginExtension, HistoryController {

	/**
	 * Logger for logging.
	 */
	private static Logger logger = Logger.getLogger(OxygenGitPluginExtension.class);

	/**
	 * ID of the Git staging view. Defined in plugin.xml.
	 */
	static final String GIT_STAGING_VIEW = "GitStagingView";
	/**
	 * ID of the Git History view.
	 */
	public static final String GIT_HISTORY_VIEW = "GitHistoryView";

	/**
	 * Refresh support.
	 */
	final PanelRefresh gitRefreshSupport = new PanelRefresh();

	/**
	 * Staging panel.
	 */
  private StagingPanel stagingPanel;
  /**
   * Plugin workspace access.
   */
  private StandalonePluginWorkspace pluginWorkspaceAccess;
	
	/**
   * History view.
   */
  private HistoryPanel historyView;
	
	/**
	 * @see ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension#applicationStarted(ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace)
	 */
	@Override
	public void applicationStarted(final StandalonePluginWorkspace pluginWorkspaceAccess) {
	  this.pluginWorkspaceAccess = pluginWorkspaceAccess;
		try {
		  // Uncomment this to start with fresh options. For testing purposes
//			PluginWorkspaceProvider.getPluginWorkspace().getOptionsStorage().setOption("GIT_PLUGIN_OPTIONS", null); NOSONAR

		  if (!"true".equals(System.getProperty(GitAddonSystemProperties.USE_JSCH_FOR_SSH_OPERATIONS))) {
  		  org.eclipse.jgit.transport.SshSessionFactory.setInstance(
  		      new org.eclipse.jgit.transport.sshd.SshdSessionFactory(null, new ResolvingProxyDataFactory()));
		  }
		  
		  AuthenticationInterceptor.install();

			GitController stageController = new GitController();
			
			ProjectViewManager.addPopUpMenuCustomizer(
			    pluginWorkspaceAccess,
			    new GitMenuActionsProvider(pluginWorkspaceAccess, stageController));

			pluginWorkspaceAccess.addViewComponentCustomizer(
			    new ViewComponentCustomizer() {// NOSONAR
				/**
				 * @see ro.sync.exml.workspace.api.standalone.ViewComponentCustomizer#customizeView(ro.sync.exml.workspace.api.standalone.ViewInfo)
				 */
			  @Override
			  public void customizeView(ViewInfo viewInfo) {
			    // The constant's value is defined in plugin.xml
			    if (GIT_STAGING_VIEW.equals(viewInfo.getViewID())) {
			      stagingPanel = new StagingPanel(gitRefreshSupport, stageController, OxygenGitPluginExtension.this);
			      gitRefreshSupport.setPanel(stagingPanel);
			      viewInfo.setComponent(stagingPanel);
			      
			      GitAccess.getInstance().addGitListener(new GitEventAdapter() {
			        @Override
			        public void repositoryIsAboutToOpen(File repo) {
			          SwingUtilities.invokeLater(() -> viewInfo.getComponent().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)));
			        }
			        @Override
              public void repositoryChanged() {
			          SwingUtilities.invokeLater(() -> viewInfo.getComponent().setCursor(Cursor.getDefaultCursor()));
			        }
			        @Override
              public void repositoryOpeningFailed(File repo, Throwable ex) {
			          SwingUtilities.invokeLater(() -> viewInfo.getComponent().setCursor(Cursor.getDefaultCursor()));
			        }
			       
			        private Timer cursorTimer = new Timer(
			            1000,
			            e -> SwingUtilities.invokeLater(() -> viewInfo.getComponent().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR))));
			        @Override
			        public void stateChanged(com.oxygenxml.git.view.event.GitEvent changeEvent) {
                GitCommand cmd = changeEvent.getGitCommand();
                GitCommandState cmdState = changeEvent.getGitComandState();
                if (cmdState == GitCommandState.STARTED) {
                  cursorTimer.restart();
                } else if (cmdState == GitCommandState.SUCCESSFULLY_ENDED
                    || cmdState == GitCommandState.FAILED) {
                  cursorTimer.stop();
                  SwingUtilities.invokeLater(() -> viewInfo.getComponent().setCursor(Cursor.getDefaultCursor()));
                
                  if (cmd == GitCommand.CONTINUE_REBASE) {
                    gitRefreshSupport.call();
                  }
                }
			        }
			      });
			      
			      // Start the thread that populates the view.
			      gitRefreshSupport.call();
			      
			      viewInfo.setIcon(Icons.getIcon(Icons.GIT_ICON));
			      viewInfo.setTitle(Translator.getInstance().getTranslation(Tags.GIT_STAGING));
					} else if (GIT_HISTORY_VIEW.equals(viewInfo.getViewID())) {
					  historyView = new HistoryPanel(stageController);
            viewInfo.setComponent(historyView);
            
            viewInfo.setIcon(Icons.getIcon(Icons.GIT_HISTORY));
            viewInfo.setTitle(Translator.getInstance().getTranslation(Tags.GIT_HISTORY));
					}
				}
			});

			final JFrame parentFrame = (JFrame) pluginWorkspaceAccess.getParentFrame();
			
			// Present the view to the user if it is the first run of the plugin
			parentFrame.addComponentListener(new ComponentAdapter() {
				@Override
				public void componentShown(ComponentEvent e) {
					String key = "view.presented.on.first.run";
					String firstRun = pluginWorkspaceAccess.getOptionsStorage().getOption(key, null);
					if (firstRun == null) {
						// This is the first run of the plugin.
						pluginWorkspaceAccess.showView(GIT_STAGING_VIEW, false);
						pluginWorkspaceAccess.getOptionsStorage().setOption(key, "true");
					}
				}

			});

			// Call the refresh command when the Oxygen window is activated
			parentFrame.addWindowListener(new WindowAdapter() {

			  /**
			   * <code>true</code> to refresh.
			   */
				private boolean refresh = false;

				@Override
				public void windowActivated(WindowEvent e) {
					super.windowActivated(e);
					if (refresh && stagingPanel != null && stagingPanel.isShowing()) {
						gitRefreshSupport.call();
					}
					refresh = false;
				}

				@Override
				public void windowDeactivated(WindowEvent e) {
					super.windowDeactivated(e);
					SwingUtilities.invokeLater(
					    new Runnable() { // NOSONAR
					  @Override
						public void run() {
							Object focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
							if (focusedWindow == null) {
								refresh = true;
							}
						}
					});
				}
			});
			
			// Redirect logging to the Log4J instance.
			com.jcraft.jsch.JSch.setLogger(new com.jcraft.jsch.Logger() {
			  @Override
        public void log(int level, String message) {
          if (logger.isDebugEnabled()) {
            logger.debug(message);
          }
        }
			  @Override
        public boolean isEnabled(int level) {
          return logger.isDebugEnabled();
        }
      });
			
		} catch (Throwable t) { // NOSONAR
			// Catch Throwable - Runtime exceptions shouldn't affect Oxygen.
			pluginWorkspaceAccess.showErrorMessage(t.getMessage());
			logger.fatal(t, t);
		}
	}

	/**
	 * @see ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension#applicationClosing()
	 */
	@Override
	public boolean applicationClosing() {
		OptionsManager.getInstance().saveOptions();
		
		if (stagingPanel != null) {
		  // Only if the view was actually created.
		  try {
		    stagingPanel.shutdown();
		  } catch (IllegalStateException e) {
		    pluginWorkspaceAccess.showView(GIT_STAGING_VIEW, true);
		    pluginWorkspaceAccess.showWarningMessage(e.getMessage());

		    // Cancel the closing.
		    return false;
		  }
		}
		// EXM-42867: wait for the refresh to execute
		gitRefreshSupport.shutdown();
		
		GitAccess.getInstance().closeRepo();
		
		// Close application.
		return true;
	}

  @Override
  public void showRepositoryHistory() {
    pluginWorkspaceAccess.showView(com.oxygenxml.git.OxygenGitPluginExtension.GIT_HISTORY_VIEW, true);
    historyView.showRepositoryHistory();    
  }

  @Override
  public void showResourceHistory(String path) {
    pluginWorkspaceAccess.showView(com.oxygenxml.git.OxygenGitPluginExtension.GIT_HISTORY_VIEW, true);
    historyView.showHistory(path);    
  }

  @Override
  public void showCommit(String filePath, RevCommit activeRevCommit) {
    pluginWorkspaceAccess.showView(com.oxygenxml.git.OxygenGitPluginExtension.GIT_HISTORY_VIEW, false);
    historyView.showCommit(filePath, activeRevCommit);
  }

}