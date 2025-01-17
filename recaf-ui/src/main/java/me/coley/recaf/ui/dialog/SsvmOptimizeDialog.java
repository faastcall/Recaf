package me.coley.recaf.ui.dialog;

import dev.xdark.ssvm.execution.ExecutionContext;
import dev.xdark.ssvm.mirror.InstanceJavaClass;
import dev.xdark.ssvm.value.TopValue;
import dev.xdark.ssvm.value.Value;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import me.coley.recaf.RecafUI;
import me.coley.recaf.code.ClassInfo;
import me.coley.recaf.code.CommonClassInfo;
import me.coley.recaf.code.MethodInfo;
import me.coley.recaf.scripting.impl.WorkspaceAPI;
import me.coley.recaf.ssvm.SsvmIntegration;
import me.coley.recaf.ssvm.VirtualMachineUtil;
import me.coley.recaf.ssvm.processing.FlowRevisitingProcessors;
import me.coley.recaf.ssvm.processing.PeepholeProcessors;
import me.coley.recaf.ui.util.Icons;
import me.coley.recaf.ui.util.Lang;
import me.coley.recaf.util.logging.Logging;
import me.coley.recaf.util.visitor.WorkspaceClassWriter;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Basic SSVM method caller dialog.
 *
 * @author Matt Coley
 */
public class SsvmOptimizeDialog extends SsvmCommonDialog {
	private static final Logger logger = Logging.get(SsvmOptimizeDialog.class);

	/**
	 * @param owner
	 * 		Method owner.
	 * @param info
	 * 		Method info.
	 * @param ssvm
	 * 		SSVM integration service.
	 */
	public SsvmOptimizeDialog(CommonClassInfo owner, MethodInfo info, SsvmIntegration ssvm) {
		super(Lang.getBinding("dialog.title.vm-peephole-invoke-args"), owner, info, ssvm);
		Button runButton = new Button();
		runButton.textProperty().bind(Lang.getBinding("dialog.vm.optimize"));
		runButton.setGraphic(Icons.getIconView(Icons.PLAY));
		runButton.setOnMousePressed(e -> {
			// Only create values when confirm button pressed
			for (InputWrapper input : inputs) {
				Value value = input.toValue();
				values[input.slot] = value;
				if (value.isWide()) {
					values[input.slot + 1] = TopValue.INSTANCE;
				}
			}
			// Run and get result
			CompletableFuture<SsvmIntegration.VmRunResult> resultFuture = ssvm.runMethod(vm, owner, info, getValues());
			resultFuture.exceptionally(SsvmIntegration.VmRunResult::new).orTimeout(10, TimeUnit.SECONDS).thenAccept((result) -> {
				// Check for error handling the task
				Throwable ex = result.getException();
				if (ex != null) {
					if (ex instanceof InterruptedException) {
						logger.error("Invoke future thread interrupted", ex);
					} else if (ex instanceof ExecutionException) {
						logger.error("Invoke future thread encountered unhandled error\n{}", encodeThrowable(ex.getCause()));
					} else if (ex instanceof TimeoutException) {
						logger.error("Invoke future thread timed out", ex);
					} else {
						logger.error("Invoke future thread encountered unhandled error\n{}", encodeThrowable(ex));
					}
					return;
				}
				// Pull new bytecode from VM
				InstanceJavaClass target = (InstanceJavaClass) vm.findBootstrapClass(owner.getName());
				ClassWriter writer = new WorkspaceClassWriter(RecafUI.getController(), ClassWriter.COMPUTE_FRAMES);
				ClassNode node = target.getNode();
				VirtualMachineUtil.restoreClass(node);
				try {
					node.accept(writer);
				} catch (Throwable t) {
					logger.error("Failed to rewrite optimized bytecode", t);
					return;
				}
				byte[] modified = writer.toByteArray();
				// Replace in workspace
				WorkspaceAPI.getPrimaryResource().getClasses().put(owner.getName(), ClassInfo.read(modified));
			});
		});
		runButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		GridPane.setFillWidth(runButton, true);
		grid.add(runButton, 0, grid.getRowCount(), 3, 1);
		// Run state tied to validity
		runButton.disableProperty().bind(totality.not());
	}

	@Override
	protected void initVm() {
		// Create new VM so we can attach processors to it.
		vm = ssvm.createVM(true, vm -> {
			Predicate<ExecutionContext> filter = ctx ->
					ctx.getOwner().getInternalName().equals(owner.getName()) &&
							ctx.getMethod().getName().equals(info.getName());
			PeepholeProcessors.installAll(vm, filter);
			FlowRevisitingProcessors.installBranchingProcessor(vm, filter);
		});
	}
}
