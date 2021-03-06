package com.oxygenxml.git.view.renderer;

import java.awt.Color;
import java.awt.Component;
import java.util.function.Supplier;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;

import com.oxygenxml.git.constants.Icons;
import com.oxygenxml.git.service.entities.FileStatus;
import com.oxygenxml.git.service.entities.GitChangeType;
import com.oxygenxml.git.utils.TreeFormatter;
import com.oxygenxml.git.view.StagingResourcesTreeModel;

/**
 * Renderer for the leafs icon in the tree, based on the git change type file
 * status.
 * 
 * @author Beniamin Savu
 *
 */
public class ChangesTreeCellRenderer extends DefaultTreeCellRenderer {
  /**
   * Default selection color.
   */
  private final Color defaultSelectionColor = getBackgroundSelectionColor();
  /**
   * Tells if a contextual menu is active over the component.
   */
  private Supplier<Boolean> contextMenuShowing;
  
  /**
   * Constructor.
   * 
   * @param contextualMenuShowing Tells if a contextual menu is active over the component.
   */
  public ChangesTreeCellRenderer(Supplier<Boolean> contextualMenuShowing) {
    this.contextMenuShowing = contextualMenuShowing;
  }
  
  /**
   * @see javax.swing.tree.DefaultTreeCellRenderer.getTreeCellRendererComponent(JTree, Object, boolean, boolean, boolean, int, boolean)
   */
	@Override
	public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf,
			int row, boolean hasFocus) {

		JLabel label = (JLabel) super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

		Icon icon = Icons.getIcon(Icons.FOLDER_TREE_ICON);
		String toolTip = null;

		StagingResourcesTreeModel model = (StagingResourcesTreeModel) tree.getModel();
		TreePath treePath = tree.getPathForRow(row);
		if (treePath != null) {
			String path = TreeFormatter.getStringPath(treePath);
			if (!"".equals(path) && model.isLeaf(TreeFormatter.getTreeNodeFromString(model, path))) {
				FileStatus file = model.getFileByPath(path);
				if (file != null) {
				  GitChangeType changeType = file.getChangeType();
				  RenderingInfo renderingInfo = RendererUtil.getRenderingInfo(changeType);
				  if (renderingInfo != null) {
				    icon = renderingInfo.getIcon();
				    toolTip = renderingInfo.getTooltip();
				  }
				} else {
				  label = null;
				}
			}
		}
		
		if (label != null) {
		  label.setIcon(icon);
		  label.setToolTipText(toolTip);

		  // Active/inactive table selection
		  if (sel) {
		    if (tree.hasFocus()) {
		      setBackgroundSelectionColor(defaultSelectionColor);
		    } else if (!contextMenuShowing.get()) {
		      // Do nor render the tree as inactive if we have a contextual menu over it.
		      setBackgroundSelectionColor(RendererUtil.getInactiveSelectionColor(tree, defaultSelectionColor));
		    }
		  }
		}

    return label;
	}
}