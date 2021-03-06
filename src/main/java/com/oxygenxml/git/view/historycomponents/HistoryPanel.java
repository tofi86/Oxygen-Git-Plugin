package com.oxygenxml.git.view.historycomponents;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import org.apache.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.jidesoft.swing.JideSplitPane;
import com.oxygenxml.git.constants.Icons;
import com.oxygenxml.git.protocol.GitRevisionURLHandler;
import com.oxygenxml.git.service.GitAccess;
import com.oxygenxml.git.service.NoRepositorySelected;
import com.oxygenxml.git.service.PrivateRepositoryException;
import com.oxygenxml.git.service.RepositoryUnavailableException;
import com.oxygenxml.git.service.RevCommitUtil;
import com.oxygenxml.git.service.RevCommitUtilBase;
import com.oxygenxml.git.service.SSHPassphraseRequiredException;
import com.oxygenxml.git.service.entities.FileStatus;
import com.oxygenxml.git.translator.Tags;
import com.oxygenxml.git.translator.Translator;
import com.oxygenxml.git.utils.Equaler;
import com.oxygenxml.git.utils.FileHelper;
import com.oxygenxml.git.view.DiffPresenter;
import com.oxygenxml.git.view.HiDPIUtil;
import com.oxygenxml.git.view.StagingResourcesTableModel;
import com.oxygenxml.git.view.dialog.UIUtil;
import com.oxygenxml.git.view.event.GitController;

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.standalone.ui.ToolbarButton;

/**
 * Presents the commits for a given resource. 
 */
public class HistoryPanel extends JPanel {
  /**
   * Logger for logging.
   */
  private static final Logger LOGGER = Logger.getLogger(HistoryPanel.class);
  /**
   * Table view that presents the commits.
   */
  private JTable historyTable;
  /**
   * Panel presenting a detailed description of the commit (author, date, etc).
   */
  private JEditorPane commitDescriptionPane;
  /**
   * The label that shows the resource for which we present the history.
   */
  private JLabel showingHistoryForRepoLabel;
  /**
   * Intercepts clicks in the commit details area.
   */
  private HistoryHyperlinkListener hyperlinkListener;
  /**
   * Commit selection listener that updates all the views with details.
   */
  private RowHistoryTableSelectionListener selectionListener;
  /**
   * The changed files from a commit.
   */
  private JTable affectedFilesTable;
  /**
   * The file path of the resource for which we are currently presenting the history. If <code>null</code>, we 
   * present the history for the entire repository.
   */
  private String activeFilePath;
  /**
   * Executes GIT commands (stage, unstage, discard, etc).
   */
  private transient GitController stageController;
  
  /**
   * Constructor.
   * 
   * @param stageController Executes a set of Git commands.
   */
  public HistoryPanel(GitController stageController) {
    this.stageController = stageController;
    setLayout(new BorderLayout());

    historyTable = UIUtil.createTable();
    historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    historyTable.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(java.awt.event.MouseEvent e) {
        if (e.isPopupTrigger()) {
          showHistoryTableContextualMenu(historyTable, e.getPoint());
        }
      }

      @Override
      public void mouseReleased(java.awt.event.MouseEvent e) {
        if (e.isPopupTrigger()) {
          showHistoryTableContextualMenu(historyTable, e.getPoint());
        }
      }
    });

    JScrollPane historyTableScrollPane = new JScrollPane(historyTable);
    historyTable.setFillsViewportHeight(true);

    commitDescriptionPane = new JEditorPane();
    initEditorPane(commitDescriptionPane);
    JScrollPane commitDescriptionScrollPane = new JScrollPane(commitDescriptionPane);
    
    affectedFilesTable = createAffectedFilesTable();
    JScrollPane affectedFilesTableScrollPane = new JScrollPane(affectedFilesTable);

    Dimension minimumSize = new Dimension(500, 150);
    commitDescriptionScrollPane.setPreferredSize(minimumSize);
    affectedFilesTableScrollPane.setPreferredSize(minimumSize);

    //----------
    // Top panel (with the "Showing history" label and the "Refresh" action
    //----------
    
    JPanel topPanel = new JPanel(new BorderLayout());
    showingHistoryForRepoLabel = new JLabel();
    topPanel.add(showingHistoryForRepoLabel, BorderLayout.WEST);
    createAndAddToolbarToTopPanel(topPanel);

    JPanel infoBoxesSplitPane = createSplitPane(
        JideSplitPane.HORIZONTAL_SPLIT,
        commitDescriptionScrollPane,
        affectedFilesTableScrollPane);
    JideSplitPane centerSplitPane = createSplitPane(
        JideSplitPane.VERTICAL_SPLIT,
        historyTableScrollPane,
        infoBoxesSplitPane);
    centerSplitPane.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
    
    //Customize the split pane.
    this.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentShown(ComponentEvent e) {
        int h = centerSplitPane.getHeight();
        centerSplitPane.setDividerLocation(0, (int)(h * 0.6));
        
        removeComponentListener(this);
      }
    });

    add(centerSplitPane, BorderLayout.CENTER);
  }

  /**
   * Creates the table that presents the files changed in a revision.
   * 
   * @return The table that presents the files.
   */
  private JTable createAffectedFilesTable() {
    JTable table = UIUtil.createResourcesTable(
        new StagingResourcesTableModel(null, true),
        () -> false);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    
    table.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(java.awt.event.MouseEvent e) {
        if (e.isPopupTrigger()) {
          showResourcesContextualMenu(table, e.getPoint());
        }
      }
      @Override
      public void mouseReleased(java.awt.event.MouseEvent e) {
        if (e.isPopupTrigger()) {
          showResourcesContextualMenu(table, e.getPoint());
        }
      }
    });
    
    return table;
  }
  
  /**
   * Show the contextual menu on the resources changed on a revision.
   * 
   * @param affectedFilesTable The table with the files from a committed on a revision.
   * @param point              The point where to show the contextual menu.
   */
  protected void showResourcesContextualMenu(JTable affectedFilesTable, Point point) {
    int rowAtPoint = affectedFilesTable.rowAtPoint(point);
    if (rowAtPoint != -1) {
      affectedFilesTable.getSelectionModel().setSelectionInterval(rowAtPoint, rowAtPoint);
      
      StagingResourcesTableModel model = (StagingResourcesTableModel) affectedFilesTable.getModel();
      int convertedSelectedRow = affectedFilesTable.convertRowIndexToModel(rowAtPoint);
      FileStatus file = model.getFileStatus(convertedSelectedRow);
      
      HistoryCommitTableModel historyTableModel = (HistoryCommitTableModel) historyTable.getModel();
      CommitCharacteristics commitCharacteristics = historyTableModel.getAllCommits().get(historyTable.getSelectedRow());
      
      JPopupMenu jPopupMenu = new JPopupMenu();
      jPopupMenu.add(createOpenFileAction(commitCharacteristics.getCommitId(), file.getFileLocation(), false));
      populateDiffActions(jPopupMenu, commitCharacteristics, file, false);
      jPopupMenu.show(affectedFilesTable, point.x, point.y);
    }
  }
  
  /**
   * Show the contextual menu on the history table.
   * 
   * @param commitResourcesTable The table with the files from a committed on a revision.
   * @param point                The point where to show the contextual menu.
   */
  protected void showHistoryTableContextualMenu(JTable historyTable, Point point) {
    if (activeFilePath != null) {
      // If we present the history for a specific file.
      int rowAtPoint = historyTable.rowAtPoint(point);
      if (rowAtPoint != -1) {
        historyTable.getSelectionModel().setSelectionInterval(rowAtPoint, rowAtPoint);

        HistoryCommitTableModel historyTableModel = (HistoryCommitTableModel) historyTable.getModel();
        int convertedSelectedRow = historyTable.convertRowIndexToModel(rowAtPoint);
        CommitCharacteristics commitCharacteristics = historyTableModel.getAllCommits().get(convertedSelectedRow);
        JPopupMenu jPopupMenu = new JPopupMenu();
        jPopupMenu.add(createOpenFileAction(commitCharacteristics.getCommitId(), activeFilePath, true));
        try {
          List<FileStatus> changes = RevCommitUtil.getChangedFiles(commitCharacteristics.getCommitId());
          Optional<FileStatus> fileStatusOptional = changes.stream().filter(f -> activeFilePath.equals(f.getFileLocation())).findFirst();
          if (fileStatusOptional.isPresent()) {
            populateDiffActions(jPopupMenu, commitCharacteristics, fileStatusOptional.get(), true);
          }
        } catch (IOException | GitAPIException e) {
          LOGGER.error(e, e);
        }
        jPopupMenu.show(historyTable, point.x, point.y);
      }
    }
  }

  /**
   * Creates an action to open a file at a given revision.
   * 
   * @param revisionID Revision ID.
   * @param filePath File path, relative to the working copy.
   * @param addFileName <code>true</code> to append the name of the file to the name of the action.
   * 
   * @return The action that will open the file when invoked.
   */
  private AbstractAction createOpenFileAction(String revisionID, String filePath, boolean addFileName) {
    String actionName = Translator.getInstance().getTranslation(Tags.OPEN);
    if (addFileName) {
      String fileName = PluginWorkspaceProvider.getPluginWorkspace().getUtilAccess().getFileName(filePath);
      actionName = MessageFormat.format(Translator.getInstance().getTranslation(Tags.OPEN_FILE), fileName);
    }
    
    return new AbstractAction(actionName) {
      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          URL fileURL = null;
          if (!GitAccess.UNCOMMITED_CHANGES.getCommitId().equals(revisionID)) {
            fileURL = GitRevisionURLHandler.encodeURL(revisionID, filePath);
          } else {
            fileURL = FileHelper.getFileURL(filePath);
          }
          
          PluginWorkspaceProvider.getPluginWorkspace().open(fileURL);
        } catch (MalformedURLException | NoRepositorySelected e1) {
          LOGGER.error(e1, e1);
          PluginWorkspaceProvider.getPluginWorkspace().showErrorMessage("Unable to open revision: " + e1.getMessage());
        } 
      }
    };
  }

  /**
   * Contributes the DIFF actions between the current revision and the previous ones on the contextual menu.
   * 
   * @param jPopupMenu Contextual menu.
   * @param commitCharacteristics Current commit data.
   * @param fileStatus File path do diff.
   * @param addFileName <code>true</code> to add the name of the file to the action's name.
   */
  private void populateDiffActions(
      JPopupMenu jPopupMenu,  
      CommitCharacteristics commitCharacteristics,
      FileStatus fileStatus,
      boolean addFileName) {
    String filePath = fileStatus.getFileLocation();
    if (!GitAccess.UNCOMMITED_CHANGES.getCommitId().equals(commitCharacteristics.getCommitId())) {
      // A revision.
      List<String> parents = commitCharacteristics.getParentCommitId();
      if (parents != null && !parents.isEmpty()) {
        try {
          RevCommit[] parentsRevCommits = RevCommitUtil.getParents(GitAccess.getInstance().getRepository(), commitCharacteristics.getCommitId());
          boolean addParentID = parents.size() > 1;
          for (RevCommit parentID : parentsRevCommits) {
            // Just one parent.
            jPopupMenu.add(createDiffAction(filePath, commitCharacteristics.getCommitId(), parentID, addParentID, addFileName));
          }
          
          String actionName = Translator.getInstance().getTranslation(Tags.COMPARE_WITH_WORKING_TREE_VERSION);
          if (addFileName) {
            String fileName = PluginWorkspaceProvider.getPluginWorkspace().getUtilAccess().getFileName(filePath);
            actionName = MessageFormat.format(Translator.getInstance().getTranslation(Tags.COMPARE_FILE_WITH_WORKING_TREE_VERSION), fileName);
          }
          
          jPopupMenu.add(new AbstractAction(actionName) {
            @Override
            public void actionPerformed(ActionEvent e) {
              try {
                DiffPresenter.showTwoWayDiffWithLocal(filePath, commitCharacteristics.getCommitId());
              } catch (MalformedURLException | NoRepositorySelected e1) {
                PluginWorkspaceProvider.getPluginWorkspace().showErrorMessage("Unable to compare: " + e1.getMessage());
                LOGGER.error(e1, e1);
              }
            }
          });
        } catch (IOException | NoRepositorySelected e2) {
          PluginWorkspaceProvider.getPluginWorkspace().showErrorMessage("Unable to compare: " + e2.getMessage());
          LOGGER.error(e2, e2);
        }
      }
    } else {
      // Uncommitted changes. Compare between local and HEAD.
      jPopupMenu.add(new AbstractAction(
          Translator.getInstance().getTranslation(Tags.OPEN_IN_COMPARE)) {
        @Override
        public void actionPerformed(ActionEvent e) {
          DiffPresenter.showDiff(fileStatus, stageController);
        }
      });
    }
  }

  /**
   * Creates an action that invokes Oxygen's DIFF.
   * 
   * @param filePath File to compare. Path relative to the working tree.
   * @param commitID The current commit id. First version to compare.
   * @param parentRevCommit The parent revision. Second version to comapre.
   * @param addParentIDInActionName <code>true</code> to put the ID of the parent version in the action's name.
   * @param addFileName <code>true</code> to add the file name to the action's name. 
   * 
   * @return The action that invokes the DIFF.
   */
  private AbstractAction createDiffAction(
      String filePath,
      String commitID, 
      RevCommit parentRevCommit,
      boolean addParentIDInActionName, 
      boolean addFileName) {
    
    String actionName = Translator.getInstance().getTranslation(Tags.COMPARE_WITH_PREVIOUS_VERSION);
    if (addFileName) {
      String fileName = PluginWorkspaceProvider.getPluginWorkspace().getUtilAccess().getFileName(filePath);
      actionName = MessageFormat.format(Translator.getInstance().getTranslation(Tags.COMPARE_FILE_WITH_PREVIOUS_VERSION), fileName);
    }
    if (addParentIDInActionName) {
      actionName += " " + parentRevCommit.abbreviate(RevCommitUtilBase.ABBREVIATED_COMMIT_LENGTH).name();
    }
    
    return new AbstractAction(actionName) {
      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          DiffPresenter.showTwoWayDiff(commitID, parentRevCommit.name(), filePath);
        } catch (MalformedURLException e1) {
          PluginWorkspaceProvider.getPluginWorkspace().showErrorMessage("Unable to compare: " + e1.getMessage());
          LOGGER.error(e1, e1);
        }
      }
    };
    
  }

  /**
   * Creates a split pane and puts the two components in it.
   * 
   * @param splitType {@link JideSplitPane#HORIZONTAL_SPLIT} or {@link JideSplitPane#VERTICAL_SPLIT} 
   * @param firstComponent Fist component to add.
   * @param secondComponent Second component to add.
   * 
   * @return The split pane.
   */
  private JideSplitPane createSplitPane(int splitType, JComponent firstComponent, JComponent secondComponent) {
    JideSplitPane splitPane = new JideSplitPane(splitType);
    
    splitPane.add(firstComponent);
    splitPane.add(secondComponent);
    
    splitPane.setDividerSize(5);
    splitPane.setContinuousLayout(true);
    splitPane.setOneTouchExpandable(false);
    splitPane.setBorder(null);
    
    return splitPane;
  }
  
  /**
   * Initializes the split with the proper font and other properties.
   * 
   * @param editorPane Editor pane to initialize.
   */
  private static void initEditorPane(JEditorPane editorPane) {
    // Forces the JEditorPane to take the font from the UI, rather than the HTML document.
    editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    Font font = UIManager.getDefaults().getFont("TextArea.font");
    if(font != null){
      editorPane.setFont(font);
    }
    editorPane.setBorder(null);
    editorPane.setContentType("text/html");
    editorPane.setEditable(false);

  }

  /**
   * Creates the toolbar. 
   * 
   * @param topPanel Parent for the toolbar.
   */
  private void createAndAddToolbarToTopPanel(JPanel topPanel) {
    JToolBar toolbar = new JToolBar();
    toolbar.setOpaque(false);
    toolbar.setFloatable(false);
    topPanel.add(toolbar, BorderLayout.EAST);
    
    // Add the Refresh action to the toolbar
    Action refreshAction = new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        showHistory(activeFilePath, true);
      }
    };
    refreshAction.putValue(Action.SMALL_ICON, Icons.getIcon(Icons.REFRESH_ICON));
    refreshAction.putValue(Action.SHORT_DESCRIPTION, Translator.getInstance().getTranslation(Tags.REFRESH));
    ToolbarButton refreshButton = new ToolbarButton(refreshAction, false);
    toolbar.add(refreshButton);
    
    add(topPanel, BorderLayout.NORTH);
  }

  /**
   * Shows the commit history for the entire repository.
   */
  public void showRepositoryHistory() {
    showHistory(null, true);
  }
  
  /**
   * Shows the commit history for the entire repository.
   * 
   * @param filePath File for which to present the commit that changed him.
   */
  public void showHistory(String filePath) {
    showHistory(filePath, false);
  }

  /**
   * Shows the commit history for the entire repository.
   * 
   * @param filePath File for which to present the commit that changed him.
   * @param force    <code>true</code> to recompute the history data,
   *                     even if the view already presents the history
   *                      for the given resource.
   */
  private void showHistory(String filePath, boolean force) {
    // Check if we don't already present the history for this path!!!!
    if (force || !Equaler.verifyEquals(filePath, activeFilePath)) {
      this.activeFilePath = filePath;
      GitAccess gitAccess = GitAccess.getInstance();

      try {
        // Make sure we know about the remote as well, to present data about the upstream branch.
        gitAccess.fetch();

        File directory = gitAccess.getWorkingCopy();
        if (filePath != null) {
          directory = new File(directory, filePath);
        }
        showingHistoryForRepoLabel.setText(
            Translator.getInstance().getTranslation(Tags.SHOWING_HISTORY_FOR) + " " + directory.getName());
        showingHistoryForRepoLabel.setToolTipText(directory.getAbsolutePath());
        showingHistoryForRepoLabel.setBorder(BorderFactory.createEmptyBorder(0,2,5,0));

        // Install selection listener.
        if (selectionListener != null) {
          historyTable.getSelectionModel().removeListSelectionListener(selectionListener);
        }
        
        StagingResourcesTableModel dataModel = (StagingResourcesTableModel) affectedFilesTable.getModel();
        dataModel.setFilesStatus(Collections.emptyList());
        commitDescriptionPane.setText("");

        List<CommitCharacteristics> commitCharacteristicsVector = gitAccess.getCommitsCharacteristics(filePath);
        historyTable.setModel(new HistoryCommitTableModel(commitCharacteristicsVector));
        updateHistoryTableWidths();
        
        historyTable.setDefaultRenderer(CommitCharacteristics.class, new CommitMessageTableRenderer(gitAccess.getRepository()));
        historyTable.setDefaultRenderer(Date.class, new DateTableCellRenderer("d MMM yyyy HH:mm"));
        TableColumn authorColumn = historyTable.getColumn(Translator.getInstance().getTranslation(Tags.AUTHOR));
        authorColumn.setCellRenderer(
            new DefaultTableCellRenderer() { // NOSONAR
          @Override
          public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
              boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String text = label.getText();
            int indexOfLT = text.indexOf(" <");
            if (indexOfLT != -1) {
              text = text.substring(0, indexOfLT);
            }
            label.setText(text);
            return label;
          }
        });
        
        selectionListener = new RowHistoryTableSelectionListener(
            historyTable, commitDescriptionPane, commitCharacteristicsVector, affectedFilesTable);
        historyTable.getSelectionModel().addListSelectionListener(selectionListener);

        // Install hyperlink listener.
        if (hyperlinkListener != null) {
          commitDescriptionPane.removeHyperlinkListener(hyperlinkListener);  
        }
        hyperlinkListener = new HistoryHyperlinkListener(historyTable, commitCharacteristicsVector);
        commitDescriptionPane.addHyperlinkListener(hyperlinkListener);

        // Select the local branch HEAD.
        if (!commitCharacteristicsVector.isEmpty()) {
          Repository repository = gitAccess.getRepository();
          String fullBranch = repository.getFullBranch();
          Ref branchHead = repository.exactRef(fullBranch);
          ObjectId objectId = branchHead.getObjectId();
          selectCommit(objectId);
        }

      } catch (NoRepositorySelected | SSHPassphraseRequiredException | PrivateRepositoryException | RepositoryUnavailableException | IOException e) {
        LOGGER.debug(e, e);
        PluginWorkspaceProvider.getPluginWorkspace().showErrorMessage("Unable to present history because of: " + e.getMessage());
      }
    }
  }

  /**
   * Distribute widths to the columns according to their content.
   */
  private void updateHistoryTableWidths() {
    int dateColWidth = scaleColumnsWidth(100);
    int authorColWidth = scaleColumnsWidth(120);
    int commitIdColWidth = scaleColumnsWidth(80);
    
    TableColumnModel tcm = historyTable.getColumnModel();
    TableColumn column = tcm.getColumn(0);
    column.setPreferredWidth(historyTable.getWidth() - authorColWidth - authorColWidth - dateColWidth);
    
    column = tcm.getColumn(1);
    column.setPreferredWidth(dateColWidth);

    column = tcm.getColumn(2);
    column.setPreferredWidth(authorColWidth);

    column = tcm.getColumn(3);
    column.setPreferredWidth(commitIdColWidth);
  }
  
  /**
   * Applies a scaling factor depending if we are on a hidpi display.
   * 
   * @param width Width to scale.
   * 
   * @return A scaled width.
   */
  public static int scaleColumnsWidth(int width) {
    float scalingFactor = (float) 1.0;
    if (HiDPIUtil.isRetinaNoImplicitSupport()) {
      scalingFactor = HiDPIUtil.getScalingFactor();
    }
    
    return (int) (scalingFactor * width);
  }
  

  /**
   *  Shows the commit history for the given file.
   *  
   * @param filePath Path of the file, relative to the working copy.
   * @param activeRevCommit The commit to select in the view.
   */
  public void showCommit(String filePath, RevCommit activeRevCommit) {
    showHistory(filePath);
    if (activeRevCommit != null) {
      ObjectId id = activeRevCommit.getId();
      selectCommit(id);
    }
  }

  /**
   * Selects the commit with the given ID.
   * 
   * @param id Id of the repository to select.
   */
  private void selectCommit(ObjectId id) {
    HistoryCommitTableModel model =  (HistoryCommitTableModel) historyTable.getModel();
    List<CommitCharacteristics> commits = model.getAllCommits();
    for (int i = 0; i < commits.size(); i++) {
      CommitCharacteristics commitCharacteristics = commits.get(i);
      if (id.getName().equals(commitCharacteristics.getCommitId())) {
        final int sel = i;
        SwingUtilities.invokeLater(() -> {
          historyTable.scrollRectToVisible(historyTable.getCellRect(sel, 0, true));
          historyTable.getSelectionModel().setSelectionInterval(sel, sel);
        });
        break;
      }
    }
  }
}
