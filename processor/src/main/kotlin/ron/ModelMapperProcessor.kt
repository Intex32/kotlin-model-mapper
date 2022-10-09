package ron

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.symbol.Variance.*
import com.google.devtools.ksp.validate
import java.io.OutputStream

class ModelMapperProcessor(
    private val options: Map<String, String>,
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {

    operator fun OutputStream.plusAssign(str: String) {
        this.write(str.toByteArray())
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation(ModelMapper::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()

        if (!symbols.iterator().hasNext())
            return emptyList()

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false, *resolver.getAllFiles().toList().toTypedArray()),
            packageName = "ron",
            fileName = "GeneratedMapperFunctions"
        )

        file += "package ron\n\n"
        symbols.forEach { it.accept(Visitor(file), Unit) }
        file.close()

        return symbols
            .filterNot { it.validate() }
            .toList()
    }

    inner class Visitor(private val file: OutputStream) : KSVisitorVoid() {

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if (classDeclaration.classKind != ClassKind.CLASS || classDeclaration.modifiers.none { it == Modifier.DATA })
                return logger.error("origin type must be a data class", classDeclaration)

            val annotations = classDeclaration.annotations
                .filter { it.shortName.asString() == ModelMapper::class.simpleName }

            annotations.forEach { annotation ->
                val targetTypeArgument = annotation.arguments
                    .first { arg -> arg.name?.asString() == ModelMapper::target.name }
                val targetType = targetTypeArgument.value as KSType

                val targetClassDeclaration = targetType.declaration
                if (targetClassDeclaration !is KSClassDeclaration || targetClassDeclaration.classKind != ClassKind.CLASS || targetClassDeclaration.modifiers.none { it == Modifier.DATA })
                    return logger.error("target type must be a data class", classDeclaration)

                val originProperties = classDeclaration.getAllProperties()
                    .filter { it.extensionReceiver == null } // ignore extension properties
                    .filter { it.validate() }
                val targetProperties = targetClassDeclaration.getAllProperties()
                    .filter { it.extensionReceiver == null } // ignore extension properties
                    .filter { it.validate() }

                generateMainCode(classDeclaration, originProperties, targetClassDeclaration, targetProperties)
            }
        }

        private fun generateMainCode(originClass: KSClassDeclaration, originProperties: Sequence<KSPropertyDeclaration>, targetClass: KSClassDeclaration, targetProperties: Sequence<KSPropertyDeclaration>) {
            file += "inline fun ${originClass.qualifiedName!!.asString()}.mapTo${targetClass.simpleName.asString()}(\n"

            // Iterating through each property to translate them to function arguments.
            targetProperties.forEach { targetProperty ->
                // Generating argument name.
                val argumentName = targetProperty.simpleName.asString()
                file += "    $argumentName: "

                val originProperty = originProperties.find { it.simpleName.asString() == targetProperty.simpleName.asString() }
                if (originProperty == null) {
                    visitTypeReference(targetProperty.type, Unit)
                } else {
                    file += "("
                    visitTypeReference(originProperty.type, Unit)
                    file += ") -> "
                    visitTypeReference(targetProperty.type, Unit)

                    // add default value if types identical
                    if (originProperty.type.resolve().declaration.qualifiedName == targetProperty.type.resolve().declaration.qualifiedName && originProperty.type.resolve().isMarkedNullable == targetProperty.type.resolve().isMarkedNullable)
                        file += " = { it }"
                }
                file += ",\n"
            }

            file += ") = ${targetClass.qualifiedName!!.asString()}(\n"

            targetProperties.forEach { targetProperty ->
                val argumentName = targetProperty.simpleName.asString()
                file += "    ${targetProperty.simpleName.asString()} = $argumentName"

                val originProperty = originProperties.find { it.simpleName.asString() == targetProperty.simpleName.asString() }
                if (originProperty != null) {
                    file += "(this.${originProperty.simpleName.asString()})"
                }
                file += ",\n"
            }

            file += ")\n\n"
        }

        override fun visitTypeReference(typeReference: KSTypeReference, data: Unit) {
            val resolvedType = typeReference.resolve()
            file += resolvedType.declaration.qualifiedName?.asString()
                ?: return logger.error("invalid type", typeReference)

            // generating generic parameters if any
            val genericArguments: List<KSTypeArgument> = typeReference.element?.typeArguments ?: emptyList()
            visitTypeArguments(genericArguments)

            // handling nullability
            file += if (resolvedType.nullability == Nullability.NULLABLE) "?" else ""
        }

        private fun visitTypeArguments(typeArguments: List<KSTypeArgument>) {
            if (typeArguments.isNotEmpty()) {
                file += "<"
                typeArguments.forEachIndexed { i, arg ->
                    visitTypeArgument(arg, data = Unit)
                    if (i < typeArguments.lastIndex) file += ", "
                }
                file += ">"
            }
        }

        override fun visitTypeArgument(typeArgument: KSTypeArgument, data: Unit) {
            // handling KSP options, specified in the consumer's build.gradle(.kts) file
            if (options["ignoreGenericArgs"] == "true") {
                file += "*"
                return
            }

            when (val variance: Variance = typeArgument.variance) {
                STAR -> {
                    file += "*"
                    return
                }

                COVARIANT, CONTRAVARIANT -> {
                    file += variance.label
                    file += " "
                }

                INVARIANT -> {}
            }
            val resolvedType: KSType? = typeArgument.type?.resolve()
            file += resolvedType?.declaration?.qualifiedName?.asString() ?: run {
                logger.error("invalid type argument", typeArgument)
                return
            }

            // generating nested generic parameters if any
            val genericArguments: List<KSTypeArgument> = typeArgument.type?.element?.typeArguments ?: emptyList()
            visitTypeArguments(genericArguments)

            // handling nullability
            file += if (resolvedType?.nullability == Nullability.NULLABLE) "?" else ""
        }
    }
}