package com.iwaiwa;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Verilog {
	private ExpansionConf expansion_conf = null;
	private ArrayList<String> file = new ArrayList<String>();
	private ArrayList<String> result = new ArrayList<String>();
	private ArrayList<String> other_module = new ArrayList<String>();
	private String module_definition = null;
	private ArrayList<String> input_definition = new ArrayList<String>();
	private ArrayList<String> output_definition = new ArrayList<String>();
	private ArrayList<String> ppis = new ArrayList<String>();
	private ArrayList<String> ppos = new ArrayList<String>();
	private ArrayList<String> wire_definition = new ArrayList<String>();
	private ArrayList<String> ffs_definition = new ArrayList<String>();
	private ArrayList<String> ccs_definition = new ArrayList<String>();
	private ArrayList<String> assign_definition = new ArrayList<String>();
	private ArrayList<String> ppi_connect = new ArrayList<String>();
	private ArrayList<String> ppo_connect = new ArrayList<String>();
	private int number_of_additional_inv = 0;
	private PrimaryPinName pp_names = new PrimaryPinName();

	private Pattern modname_regex = Pattern.compile("\\s*module\\s+(\\S+)\\s*\\((.+)\\)\\s*;.*");

	/**
	 * Verilog Netlistクラス
	 * @param conf 時間展開の設定ファイル、expansion.confの情報クラス
	 */
	public Verilog( ExpansionConf conf ) {
		expansion_conf = conf;
		try {
			BufferedReader br = new BufferedReader( new FileReader(expansion_conf.getInput_file()) );
			String line = null;
			while( (line=br.readLine()) != null ) {
				file.add(line);
			}
			br.close();
		} catch( Exception e ) {
			e.printStackTrace();
		}
		String top_module_name = expansion_conf.getTop_module();
		if( top_module_name != null ) {
			ArrayList<String> top_module = new ArrayList<String>();
			boolean top_flag   = false;
			boolean other_flag = false;
			for( int i=0; i<file.size(); i++ ) {
				if( file.get(i).matches("\\s*module\\s+"+top_module_name+".*") ) {
					top_flag = true;
				} else if( file.get(i).matches("\\s*module\\s+.*") ) {
					other_flag = true;
				}
				if( top_flag ) {
					top_module.add(file.get(i));
				}
				if( other_flag ) {
					other_module.add(file.get(i));
				}
				if( file.get(i).matches("\\s*endmodule.*") ) {
					top_flag   = false;
					other_flag = false;
				}
			}
			file = top_module;
		}
	}

	public void printVerilog() {
		for( int i=0; i<result.size(); i++ ) {
			System.out.println(result.get(i));
		}
	}
	public void writeVerilog( String file ) {
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(file));
			for( int i=0; i<result.size(); i++ ) {
				bw.write(result.get(i));
				bw.newLine();
			}
			bw.close();
		} catch( Exception e ) { e.printStackTrace(); }
	}

	/**
	 * 組合せ回路部分のみを抽出してファイルを作成
	 */
	public void generateCombCicuit() {
		this.extractDefinition();	// 記述を分割して抽出
		// PPIとPPOの作成
		PseudoPrimaryPinAccessor pppa= new PseudoPrimaryPinAccessor( expansion_conf, ffs_definition );
		ppis = pppa.getPPIs();
		ppos = pppa.getPPOs();
		this.removeFilpFlops();
		module_definition = this.addPseudoPrimaryPinsToModuleDefinition();

		// input宣言の中に含まれるclock/resetを除去
		for( int i=0; i<input_definition.size(); i++ )
			input_definition.set(i, expansion_conf.removeClockFromInputDefinition(input_definition.get(i)));
		pp_names.setPIs( input_definition );
		pp_names.setPOs( output_definition );
		pp_names.setPPIs( ppis );
		pp_names.setPPOs( ppos );


		for( int i=0; i<other_module.size(); i++ ) {
			result.add(other_module.get(i));
		}
		result.add("");
		result.add(module_definition);
		result.add("// begining of the input definition.");
		for( int i=0; i<input_definition.size(); i++ ) {	result.add("\t " + input_definition.get(i)); }
		for( int i=0; i<ppis.size(); i++ ) {				result.add("\t " + ppis.get(i)); }
		result.add("// begining of the output definition.");
		for( int i=0; i<output_definition.size(); i++ ) { 	result.add("\t" + output_definition.get(i)); }
		for( int i=0; i<ppos.size(); i++ ) {				result.add("\t" + ppos.get(i)); }
		result.add("\n// begining of the wire definition.");
		for( int i=0; i<wire_definition.size(); i++ ) {		result.add("\t" + wire_definition.get(i).replaceAll("^\\s+", "")); }
		result.add("\n// begining of the combinational circuit");
		for( int i=0; i<ccs_definition.size(); i++ ) {		result.add("\t" + ccs_definition.get(i).replaceAll("^\\s+", "")); }
		result.add("\n// begining of the connection of internal wire");
		for( int i=0; i<assign_definition.size(); i++ ) {	result.add("\t" + assign_definition.get(i)); }
		result.add("\n// begining of the connection from ppi");
		for( int i=0; i<ppi_connect.size(); i++ ) {			result.add("\tassign " + ppi_connect.get(i)); }
		result.add("\n// begining of the connection to ppo");
		for( int i=0; i<ppo_connect.size(); i++ ) {			result.add("\tassign " + ppo_connect.get(i)); }
		result.add("endmodule");
	}


	/**
	 * 単一モジュールが記述されたファイルから定義を分割
	 */
	private void extractDefinition() {
		// 改行の除去
		ArrayList<String> rm_return = new ArrayList<String>();
		boolean flag = false;
		StringBuffer sb = new StringBuffer();
		for( int i=0; i<file.size(); i++ ) {
			sb.append(file.get(i));
			if( file.get(i).matches(".+;\\s*(//)?.*") || file.get(i).matches("^\\s*endmodule") ) {
				flag = true;
			}
			if( flag ) {
				flag = false;
				// スペースが２つ以上ある場合は除去(１つに変換)
				rm_return.add(sb.toString().replaceAll("\\s{2,}", " "));
				sb = new StringBuffer();
			}
		}

		// 定義の整理
		for( String line: rm_return ) {
			if( line.matches("\\s*module\\s+\\S+\\s*\\(.+\\);.*") ) {
				module_definition = line;
			} else if( line.matches("\\s*input\\s+[\\S]+.*;.*") ) {
				input_definition.add(line);
			} else if( line.matches("\\s*output\\s+.+;.*") ) {
				output_definition.add(line);
			} else if( line.matches("\\s*wire\\s+.+;.*") ) {
				wire_definition.add(line);
			} else if( line.matches("\\s*"+expansion_conf.getRegex_ff_types()+"\\s+\\S+\\s*\\(.+\\);.*") ) {
				ffs_definition.add(line);
			} else if( line.matches("\\s*\\w+\\s+\\S+\\s*\\(.+\\)\\s*;.*") ) {
				ccs_definition.add(line);
			} else if( line.matches("\\s*[A-Z\\d]+\\s+[\\S]+\\d+\\s*\\(.+\\);.*") ) {
				ccs_definition.add(line);
			} else if( line.matches("\\s*assign\\s+\\S+\\s*=\\s*.+;.*") ) {
				assign_definition.add(line);
			} else if( line.matches("\\s*endmodule.*") ) {
			} else {
				System.out.println("想定外の行：" + line);
			}
		}
	}

	/**
	 * フリップフロップを除去して，PPIとPPOに接続します<br>
	 * ここでインタフェースはPPI PPOともにFF１つに対して１つずつ生成されます
	 */
	private void removeFilpFlops() {
		Pattern input_regex = Pattern.compile("\\s*("+expansion_conf.getRegex_ff_types()+")\\s+(\\S+)\\s*\\((.+)\\);.*");
		for( String ff : ffs_definition ) {
			Matcher input_match = input_regex.matcher(ff);
			if( input_match.matches() ) {
				String ff_type = input_match.group(1);
				String instance_name = input_match.group(2);
				for( String pin : input_match.group(3).split(",") ) {
					this.connectPPIs(ff_type, instance_name, pin);
					this.connectPPOs(ff_type, instance_name, pin);
				}
			} else {
				System.out.println("想定外のFF宣言：" + ff);
			}
		}
	}

	/**
	 * PPIと現存のwireを接続します
	 * @param ff_type FFの種類(FD1, FD2Sなど)
	 * @param instance_name FFのインスタンス名（PPIの名前生成に利用します）
	 * @param internal_pin FF内でのピン名とその接続情報
	 */
	private void connectPPIs( String ff_type, String instance_name, String internal_pin ) {
		Pattern q_regex = Pattern.compile("\\s*\\."+expansion_conf.getDataOutput(ff_type)+"\\(\\s*(\\S+)\\s*\\)\\s*.*");
		Matcher q_match = q_regex.matcher(internal_pin);
		if( q_match.matches() ) {
			ppi_connect.add(q_match.group(1) + "\t= " + "ppi_" + instance_name + " ;");
		}
		Pattern qn_regex = Pattern.compile("\\s*\\."+expansion_conf.getInvertedDataOutput(ff_type)+"\\(\\s*(\\S+)\\s*\\)\\s*.*");
		Matcher qn_match = qn_regex.matcher(internal_pin);
		if( qn_match.matches() ) {
			ccs_definition.add(expansion_conf.getInv_type()+" UN" + number_of_additional_inv + " ( ."
				+expansion_conf.getInv_input()+"( ppi_" + instance_name + " ), ."
				+expansion_conf.getInv_output()+"( " + qn_match.group(1) + " ) );");
			number_of_additional_inv++;
		}
	}
	/**
	 * PPOと現存のwireを接続します
	 * @param ff_type FFの種類(FD1, FD2Sなど)
	 * @param instance_name FFのインスタンス名（PPOの名前生成に利用します）
	 * @param internal_pin FF内でのピン名とその接続情報
	 */
	private void connectPPOs( String ff_type, String instance_name, String internal_pin ) {
		Pattern d_regex = Pattern.compile("\\s*\\."+expansion_conf.getDataInput(ff_type)+"\\(\\s*(\\S+)\\s*\\)\\s*.*");
		Matcher d_match = d_regex.matcher(internal_pin);
		if( d_match.matches() ) {
			ppo_connect.add("ppo_" + instance_name + "\t= " + d_match.group(1) + " ;");
		}
	}

	/**
	 * モジュール宣言の外部入出力ピンの指定に疑似外部入出力を付加します
	 * @return module定義文
	 */
	private String addPseudoPrimaryPinsToModuleDefinition() {
		String mod_name = "";
		LinkedList<String> primary_pins = new LinkedList<String>();
		Matcher modname_match = modname_regex.matcher(module_definition);
		if( modname_match.matches() ) {
			mod_name = modname_match.group(1);
			String[] pins = modname_match.group(2).replaceAll("\\s", "").split(",");
			for( String pin : pins ) {
				if( !(expansion_conf.isClock(pin)) ) { // clkとrst宣言は除去
					primary_pins.add(pin);
				}
			}
		} else {
			System.out.println("Error: can't analyze the module info!");
			System.out.println(module_definition);
			System.exit(0);
		}
		for( int i=0; i<ppis.size(); i++ ) {
			primary_pins.add(ppis.get(i).replaceAll("input( \\[\\d+:\\d+\\] )?", "").replace(";", ""));
		}
		for( int i=0; i<ppos.size(); i++ ) {
			primary_pins.add(ppos.get(i).replaceAll("output( \\[\\d+:\\d+\\] )?", "").replace(";", ""));
		}
		Object[] sort_pis = primary_pins.toArray();
		Arrays.sort(sort_pis);

		StringBuffer sb = new StringBuffer();
		sb.append("module ");
		sb.append(mod_name);
		sb.append(" ( ");
//		for( int i=0; i<primary_pins.size(); i++ ) {
//			sb.append(primary_pins.get(i));
//			sb.append(" , ");
//		}
		for( int i=0; i<sort_pis.length; i++ ) {
			sb.append(sort_pis[i]);
			sb.append(" , ");
		}
		sb.delete(sb.length()-3, sb.length());
		sb.append(" );");

		return( sb.toString() );
	}


	public String getModuleName() {
		Matcher modname_match = modname_regex.matcher(module_definition);
		if( modname_match.matches() ) {
			return(modname_match.group(1));
		}
		return null;
	}
	public String getPinList() {
		Matcher modname_match = modname_regex.matcher(module_definition);
		if( modname_match.matches() ) {
			return(modname_match.group(2));
		}
		return null;
	}
	public PrimaryPinName getPp_names() {
		return pp_names;
	}


	/**
	 * なんかバグが超残ってそうだけど・・・ｗ
	 * @param long_term 長い文字列
	 * @return 80文字で改行した文字列
	 */
	public static String clearLine80 ( String long_term ) {
		if( long_term.length() > 80 ) {
			StringBuffer st = new StringBuffer();
			int p = long_term.lastIndexOf(",",80)+2;
			int li = 0;
			while( p > li ) {
				st.append(long_term.substring(li,p));
				st.append("\n");
				li=p;
				p = long_term.lastIndexOf(",",li+80)+2;
			}
			st.append(long_term.substring(li));
			return(st.toString());
		}

		return long_term;
	}
}