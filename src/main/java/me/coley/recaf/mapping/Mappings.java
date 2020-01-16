package me.coley.recaf.mapping;

import me.coley.recaf.workspace.*;
import org.apache.commons.io.FileUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Base for mapppings.
 *
 * @author Matt
 */
public abstract class Mappings {
	private final Set<String> updatedNames = new HashSet<>();
	private Map<String, String> mappings;
	private Map<String, String> reverseClassMappings;
	private Workspace workspace;
	private boolean checkFieldHierarchy;
	private boolean checkMethodHierarchy;
	private boolean clearDebugInfo;

	/**
	 * @param file
	 * 		Text file containing mappings.
	 * @param workspace
	 * 		Workspace to pull names from when using hierarchy lookups.
	 *
	 * @throws IOException
	 * 		Thrown if the file could not be read.
	 */
	Mappings(File file, Workspace workspace) throws IOException {
		this.workspace = workspace;
		read(file);
	}

	/**
	 * @param file
	 * 		Text file containing mappings.
	 *
	 * @throws IOException
	 * 		Thrown if the file could not be read.
	 */
	private void read(File file) throws IOException {
		String text = FileUtils.readFileToString(file, "UTF-8");
		mappings = parse(text);
		// Save inverted class name mappings for class-writing (requires ancestor analysis)
		// - Allows us to not have to recompile in ancestral order
		reverseClassMappings = mappings.entrySet()
				.stream()
				.filter(e -> !e.getKey().contains("."))
				.collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
	}

	/**
	 * See the
	 * {@link org.objectweb.asm.commons.SimpleRemapper#SimpleRemapper(Map)} docs for more
	 * information.
	 *
	 * @return ASM formatted mappings.
	 */
	public Map<String, String> getMappings() {
		return mappings;
	}

	/**
	 * In some cases automated mappings can reference fields in super-classes but specify the
	 * implementing class as the field's owner. Enabling this flag will allow the mapper to check
	 * against super-classes when mapping these cases.
	 *
	 * @return Flag for if parent classes should be checked for containing fields.
	 */
	public boolean doCheckFieldHierarchy() {
		return checkFieldHierarchy;
	}

	/**
	 * In many cases automated mappings can reference methods declared in super-classes but
	 * specify the implementing class as the method's owner. Enabling this flag will allow the
	 * mapper to check against super-classes when mapping these cases.
	 *
	 * @return Flag for if parent classes should be checked for containing methods.
	 */
	public boolean doCheckMethodHierarchy() {
		return checkMethodHierarchy;
	}

	/**
	 * @param checkFieldHierarchy Flag for if parent classes should be checked for containing fields.
	 */
	public void setCheckFieldHierarchy(boolean checkFieldHierarchy) {
		this.checkFieldHierarchy = checkFieldHierarchy;
	}

	/**
	 * @param checkMethodHierarchy Flag for if parent classes should be checked for containing methods.
	 */
	public void setCheckMethodHierarchy(boolean checkMethodHierarchy) {
		this.checkMethodHierarchy = checkMethodHierarchy;
	}

	/**
	 * Useful for clearing intentionally bad debug info like bad variable names &amp; signatures.
	 *
	 * @return Flag for removing debug information.
	 */
	public boolean doClearDebugInfo() {
		return clearDebugInfo;
	}

	/**
	 * @param clearDebugInfo
	 * 		Flag for removing debug information.
	 */
	public void setClearDebugInfo(boolean clearDebugInfo) {
		this.clearDebugInfo = clearDebugInfo;
	}

	/**
	 * Parses the mappings into the standard ASM format. See the
	 * {@link org.objectweb.asm.commons.SimpleRemapper#SimpleRemapper(Map)} docs for more
	 * information.
	 *
	 * @param text
	 * 		Text of the mappings.
	 *
	 * @return ASM formatted mappings.
	 */
	protected abstract Map<String, String> parse(String text);

	/**
	 * Applies mappings to all classes in the given resource. Return value is the map of updated
	 * classes.
	 *
	 * @param resource
	 * 		Resource containing classes.
	 *
	 * @return Map of updated classes. Keys of the old names, values of the updated code.
	 */
	public Map<String, byte[]> accept(JavaResource resource) {
		// Collect: <OldName, NewBytecode>
		Map<String, byte[]> updated = new HashMap<>();
		for(Map.Entry<String, byte[]> e : resource.getClasses().entrySet()) {
			// Skip already updated classes.
			if (updatedNames.contains(e.getKey()))
				continue;
			byte[] old = e.getValue();
			ClassReader cr = new ClassReader(old);
			accept(updated, cr);
		}
		// Update the resource's classes map
		for(Map.Entry<String, byte[]> e : updated.entrySet()) {
			String oldKey = e.getKey();
			String newKey = new ClassReader(e.getValue()).getClassName();
			if (!oldKey.equals(newKey))
				resource.getClasses().remove(oldKey);
			resource.getClasses().put(newKey, e.getValue());
		}
		// Update hierarchy graph
		workspace.getHierarchyGraph().refresh();
		return updated;
	}

	/**
	 * Applies mappings to the given class and puts the modified bytecode in the map.
	 *
	 * @param updated
	 * 		Map to collect updated values in.
	 * @param cr
	 * 		Class bytecode reader.
	 */
	public void accept(Map<String, byte[]> updated, ClassReader cr) {
		String name = cr.getClassName();
		// Skip already updated classes.
		if (updatedNames.contains(name))
			return;
		// Apply with mapper
		SimpleRecordingRemapper mapper = new SimpleRecordingRemapper(getMappings(),
				checkFieldHierarchy, checkMethodHierarchy, workspace);
		WorkspaceClassWriter cw = workspace.createWriter(ClassWriter.COMPUTE_FRAMES);
		cw.setMappings(getMappings(), reverseClassMappings);
		ClassRemapper adapter = new ClassRemapper(cw, mapper);
		int flags = ClassReader.SKIP_FRAMES;
		if (clearDebugInfo)
			flags |= ClassReader.SKIP_DEBUG;
		cr.accept(adapter, flags);
		// Only return the modified class if any references to the mappings were found.
		if (mapper.isDirty())
			updated.put(name, cw.toByteArray());
	}
}