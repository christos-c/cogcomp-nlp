import re
import sys
from subprocess import call, Popen
from time import sleep

with open ("src/main/lbj/CommaClassifier.lbj", "r+") as lbj_file, open(sys.argv[1], 'r') as experiments_file:
#with open ("testfile", "r+") as lbj_file, open("experiments.txt", 'r') as experiments_file, open("experimentResults.txt", 'w') as results_file:
	lbj_code = lbj_file.read()
	feature_pattern = re.compile('using .*')
	
	for feature_set in experiments_file:
		mod_lbj_code = re.sub(feature_pattern, "using " + feature_set, lbj_code)
		lbj_file.seek(0)
		lbj_file.write(mod_lbj_code)
		lbj_file.truncate()

		with  open("experimentResults.txt", 'a') as results_file:
			results_file.write(feature_set + "\n")

		print
		print "**************************STARTING FAULTY PROCESS**********************"
		print
		faulty_compile = Popen(["mvn", "lbj:compile"])
		sleep(5)
		print
		print "**************************KILLING FAULTY PROCESS**********************"
		print
		faulty_compile.kill()
		print
		print "**************************MVN LBJ:CLEAN**********************"
		print
		call(["mvn", "lbj:clean"])
		print
		print "**************************MVN LBJ:COMPILE**********************"
		print
		call(["mvn", "lbj:compile"])
		print
		print "**************************EXPERIMENT COMPLETE**********************"
		print

	lbj_file.seek(0)
	lbj_file.write(lbj_code)
	lbj_file.truncate()