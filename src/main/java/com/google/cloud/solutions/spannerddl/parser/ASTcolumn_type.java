/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.solutions.spannerddl.parser;

import com.google.cloud.solutions.spannerddl.diff.AstTreeUtils;
import java.util.Locale;

/** Abstract Syntax Tree parser object for "column_type" token */
public class ASTcolumn_type extends SimpleNode {

  public ASTcolumn_type(int id) {
    super(id);
  }

  public ASTcolumn_type(DdlParser p, int id) {
    super(p, id);
  }

  public String getLength() {
    return children[0].toString();
  }

  public boolean isArray() {
    return getTypeName().equals("ARRAY");
  }

  public ASTcolumn_type getArraySubType() {
    return (ASTcolumn_type) children[0];
  }

  @Override
  public String toString() {

    String typeName = getTypeName();
    switch (typeName) {
      case "FLOAT32":
      case "FLOAT64":
      case "INT64":
      case "BOOL":
      case "TIMESTAMP":
      case "DATE":
      case "NUMERIC":
      case "JSON":
      case "TOKENLIST":
        return typeName;
      case "STRING":
      case "BYTES":
        // length.
        return typeName + "(" + ((ASTlength) children[0]) + ")";
      case "ARRAY":
        return "ARRAY<" + ((ASTcolumn_type) children[0]) + ">";
      case "PG": // PG.pgtype
        return AstTreeUtils.tokensToString(this).toUpperCase(Locale.ROOT);
      case "STRUCT":
        if (jjtGetNumChildren() > 0) {
          return AstTreeUtils.tokensToString(this);
        } else {
          return "STRUCT <>";
        }
      case "DOTTED_PATH":
        return AstTreeUtils.tokensToString(this);
      default:
        throw new IllegalArgumentException("Unknown column type " + typeName);
    }
  }

  public String getTypeName() {
    if (jjtGetNumChildren() == 1) {
      Object child = children[0];
      if (child instanceof ASTdotted_path) {
        return "DOTTED_PATH";
      }
    }
    return jjtGetFirstToken().toString().toUpperCase(Locale.ROOT);
  }
}
